package com.buzbuz.smartautoclicker.core.script.runtime.api

data class ScreenSize(val width: Int, val height: Int)
data class ScreenPoint(val x: Int, val y: Int)
data class ScreenTapStep(val position: ScreenPoint, val pressMs: Long, val afterMs: Long)
data class ScreenRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)
enum class ScreenZoomDirection { IN, OUT }
data class ScreenGestureStep(val position: ScreenPoint?, val durationMs: Long)
data class ScreenGestureFinger(
    val start: ScreenPoint,
    val startDelayMs: Long,
    val holdMs: Long,
    val steps: List<ScreenGestureStep>,
    val keepDown: Boolean,
) {
    /** A press/release without motions uses the same 50 ms default as the public tap API. */
    val effectiveHoldMs: Long get() = if (holdMs == 0L && steps.isEmpty()) 50L else holdMs
}
enum class RecognitionKind { IMAGE, TEXT, NUMBER, COLOR }
enum class RecognitionStatus { FOUND, NOT_FOUND, INVALID_READING }
data class RecognitionQuery(
    val kind: RecognitionKind,
    val region: ScreenRegion,
    val threshold: Int,
    val reference: String? = null,
)
data class ScreenRecognition(
    val status: RecognitionStatus,
    val confidence: Double,
    val position: ScreenPoint? = null,
    val number: Double? = null,
    val size: ScreenSize? = null,
)

/** Android integration boundary. The session coordinator serializes all calls on the detector thread. */
interface ScreenAutomationBackend {
    /** Replaces the current screen image; following reads share it. Example: `backend.capture()`. */
    suspend fun capture(): ScreenSize
    /** Reads the currently captured image without taking another capture. */
    suspend fun recognize(query: RecognitionQuery): ScreenRecognition
    /** Dispatches a tap and returns when it completes, e.g. `tap(ScreenPoint(20, 30), 50)`. */
    suspend fun tap(position: ScreenPoint, durationMs: Long)
    /** Runs a bounded sequence without a pause between its taps; cancellation still stops pending taps. */
    suspend fun tapSequence(steps: List<ScreenTapStep>)
    /** Dispatches a swipe and returns when it completes. */
    suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long)
    /** Dispatches a two-finger zoom, e.g. `zoom(center, 150, 300, ScreenZoomDirection.OUT)`. */
    suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection)
    /** Dispatches one serialized multitouch gesture; held pointers are released at pause/stop. */
    suspend fun gesture(fingers: List<ScreenGestureFinger>)
    /** Releases held pointers at pause/stop. */
    suspend fun releasePointers()
    /** Drops the retained screen image and cached recognition results. */
    suspend fun invalidateObservation()
}
