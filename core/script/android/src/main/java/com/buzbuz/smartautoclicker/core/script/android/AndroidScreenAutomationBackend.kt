package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.common.actions.gesture.builder.AndroidGestureBuilder
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.detection.NumberFormatType
import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

sealed interface AndroidScriptResource {
    data class Image(val bitmap: Bitmap, val width: Int, val height: Int, val referenceScreen: ScreenSize? = null) : AndroidScriptResource
    data class Text(val alternatives: List<String>, val model: String) : AndroidScriptResource
    data class Color(val argb: Int) : AndroidScriptResource
    data class Number(val format: NumberFormatType) : AndroidScriptResource
}

/** Uses an initialized detector and accessibility executor on the session's dedicated thread.
 * Captured bitmaps are borrowed from DisplayRecorder's cache and must not be recycled here.
 * processingTag follows the existing detector contract: AppComponentsProvider.originalAppId, not an observation label.
 */
class AndroidScreenAutomationBackend(
    private val detector: ImageDetector,
    private val actions: AndroidActionExecutor,
    private val captureBitmap: suspend () -> Bitmap?,
    private val resolveResource: (String) -> AndroidScriptResource,
    private val processingTag: String,
    private val ensureOcrModels: suspend (Set<String>) -> Unit = {},
) : ScreenAutomationBackend {
    private var currentBitmap: Bitmap? = null

    override suspend fun capture(): ScreenSize {
        invalidateObservation()
        val bitmap = withTimeout(2000) {
            var captured = captureBitmap()
            while (captured == null) {
                delay(5)
                captured = captureBitmap()
            }
            captured
        }
        detector.setScreenBitmap(bitmap, processingTag)
        currentBitmap = bitmap
        return ScreenSize(bitmap.width, bitmap.height)
    }

    override suspend fun recognize(query: RecognitionQuery): ScreenRecognition {
        checkNotNull(currentBitmap) { "No script observation; capture before recognition" }
        val area = query.region.let { Rect(it.left, it.top, it.right, it.bottom) }
        val resource = query.reference?.let(resolveResource)
        when (query.kind) {
            RecognitionKind.NUMBER -> ensureOcrModels(setOf("LATIN"))
            RecognitionKind.TEXT -> if (resource is AndroidScriptResource.Text) ensureOcrModels(setOf(resource.model))
            else -> Unit
        }
        val result = when (query.kind) {
            RecognitionKind.IMAGE -> {
                require(resource is AndroidScriptResource.Image) { "reference=${query.reference}; expected an image resource" }
                val screen = checkNotNull(currentBitmap)
                val expectedWidth = resource.referenceScreen?.let { (resource.width.toDouble() * screen.width / it.width).roundToInt().coerceAtLeast(1) }
                    ?: resource.width
                val expectedHeight = resource.referenceScreen?.let { (resource.height.toDouble() * screen.height / it.height).roundToInt().coerceAtLeast(1) }
                    ?: resource.height
                detector.detectImage(resource.bitmap, expectedWidth, expectedHeight, area, query.threshold)
            }
            RecognitionKind.TEXT -> {
                require(resource is AndroidScriptResource.Text) { "reference=${query.reference}; expected a text resource" }
                detector.detectText(resource.alternatives, resource.model, area, query.threshold)
            }
            RecognitionKind.COLOR -> {
                require(resource is AndroidScriptResource.Color) { "reference=${query.reference}; expected a color resource" }
                detector.detectColor(resource.argb, area, query.threshold)
            }
            RecognitionKind.NUMBER -> {
                require(resource == null || resource is AndroidScriptResource.Number) {
                    "reference=${query.reference}; expected a number format resource"
                }
                detector.detectNumber(area, query.threshold, resource?.format ?: NumberFormatType.AUTO)
            }
        }
        val status = when {
            query.kind == RecognitionKind.NUMBER && (!result.isDetected || result.numberDetected?.isFinite() != true) -> RecognitionStatus.INVALID_READING
            result.isDetected -> RecognitionStatus.FOUND
            else -> RecognitionStatus.NOT_FOUND
        }
        return ScreenRecognition(status, result.confidenceRate,
            if (result.isDetected) ScreenPoint(result.position.x, result.position.y) else null,
            if (status == RecognitionStatus.FOUND) result.numberDetected else null,
            if (status == RecognitionStatus.FOUND && result.size.x > 0 && result.size.y > 0) ScreenSize(result.size.x, result.size.y) else null)
    }

    override suspend fun tap(position: ScreenPoint, durationMs: Long) {
        dispatch(position, position, durationMs, tap = true)
    }

    override suspend fun tapSequence(steps: List<ScreenTapStep>) {
        for (step in steps) {
            tap(step.position, step.pressMs)
            if (step.afterMs > 0) delay(step.afterMs)
        }
    }

    override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) {
        dispatch(from, to, durationMs, tap = false)
    }

    override suspend fun releasePointers() = actions.releaseHeldPointer()

    override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) {
        awaitAndroidGesture {
            actions.dispatchZoomGesture(Point(center.x, center.y), intensityPx, durationMs,
                if (direction == ScreenZoomDirection.IN) ZoomDirection.IN else ZoomDirection.OUT, null)
        }
    }

    override suspend fun gesture(fingers: List<ScreenGestureFinger>) {
        require(Build.VERSION.SDK_INT >= 26 || fingers.none { it.keepDown || it.steps.any { step -> step.position == null } }) {
            "gesture: held pointers and stationary waits require Android API 26; use initial holdMs on API 24/25"
        }
        val builder = AndroidGestureBuilder.createNewBuilder(null)
        val resolved = fingers.mapIndexed { index, finger ->
            val stroke = builder.newFinger(index, finger.startDelayMs).press(Point(finger.start.x, finger.start.y), finger.effectiveHoldMs)
            finger.steps.forEach { step ->
                val position = step.position
                if (position == null) stroke.wait(step.durationMs)
                else stroke.move(Point(position.x, position.y), step.durationMs)
            }
            checkNotNull(stroke.release(finger.keepDown).build()) { "Gesture finger $index could not be built" }
        }
        val gesture = checkNotNull(builder.build(resolved, "script-multitouch", actions.getHeldPointer(), this)) {
            "Android refused the multitouch script gesture"
        }
        awaitAndroidGesture { actions.dispatchBuiltGesture(gesture) }
    }

    override suspend fun invalidateObservation() {
        val previous = currentBitmap ?: return
        currentBitmap = null
        detector.releaseScreenBitmap(previous)
    }

    private suspend fun dispatch(from: ScreenPoint, to: ScreenPoint, durationMs: Long, tap: Boolean) {
        val builder = AndroidGestureBuilder.createNewBuilder(null)
        val fingerBuilder = builder.newFinger(0, 0)
        if (tap) fingerBuilder.press(Point(from.x, from.y), durationMs)
        else fingerBuilder.press(Point(from.x, from.y)).move(Point(to.x, to.y), durationMs)
        val finger = checkNotNull(fingerBuilder.release().build()) { "Script gesture could not be built" }
        val gesture = checkNotNull(builder.build(listOf(finger), "script", actions.getHeldPointer(), this)) {
            "Android refused the script gesture"
        }
        awaitAndroidGesture { actions.dispatchBuiltGesture(gesture) }
    }

    private suspend fun awaitAndroidGesture(dispatch: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        // Cancelling a coroutine does not withdraw an OS gesture. Let the existing executor finish tracking its
        // callback and held pointer before session cleanup releases that pointer and permits another owner.
        withContext(NonCancellable) { dispatch() }
        currentCoroutineContext().ensureActive()
    }

}
