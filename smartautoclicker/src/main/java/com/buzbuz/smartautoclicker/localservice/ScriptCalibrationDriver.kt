package com.buzbuz.smartautoclicker.localservice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageCalibration
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenRegion
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenSize
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Calibration borrows the same exclusive projection lease as execution, without starting a detector. */
class ScriptCalibrationDriver(
    context: Context,
    private val recorder: DisplayRecorder,
    private val display: DisplayConfigManager,
) {
    private val authoring = ScriptPackageCalibration(ScriptPackageRepository(File(context.filesDir, "scripts/packages")))
    private val mutex = Mutex()
    private var closed = false
    private var orientationListener: ((Context) -> Unit)? = null

    /** Starts projection after consent and foreground notification, e.g. before opening the selector. */
    suspend fun start(scope: CoroutineScope, resultCode: Int, data: Intent, onProjectionLost: () -> Unit) = mutex.withLock {
        check(!closed) { "Calibration has been closed" }
        check(orientationListener == null) { "Calibration can only start once" }
        val listener: (Context) -> Unit = {
            scope.launch {
                try {
                    mutex.withLock { if (!closed) recorder.resizeDisplay(display.displayConfig.sizePx) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { if (!closed) onProjectionLost() }
            }
        }
        orientationListener = listener
        display.addOrientationListener(listener)
        recorder.startProjection(resultCode, data) { if (!closed) onProjectionLost() }
        recorder.startScreenRecord(display.displayConfig.sizePx)
    }

    /** Captures/publishes the user's selected region as one immutable revision. */
    suspend fun save(storageId: String, name: String, image: Boolean, size: ScreenSize, area: ScreenRegion): String = mutex.withLock {
        check(!closed) { "Calibration has been closed" }
        withContext(Dispatchers.IO) {
            val png = if (!image) null else {
                val bitmap = withTimeout(2000) {
                    var latest = recorder.acquireLatestBitmap()
                    while (latest == null) { delay(5); latest = recorder.acquireLatestBitmap() }
                    latest
                }
                require(bitmap.width == size.width && bitmap.height == size.height) {
                    "screen=${bitmap.width}x${bitmap.height}; expected ${size.width}x${size.height}; retry after the display settles"
                }
                require(area.left >= 0 && area.top >= 0 && area.right > area.left && area.bottom > area.top && area.right <= size.width && area.bottom <= size.height) {
                    "region=$area; expected a nonempty area inside $size"
                }
                val cropped = Bitmap.createBitmap(bitmap, area.left, area.top, area.right - area.left, area.bottom - area.top)
                try {
                    ByteArrayOutputStream().also { output -> check(cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Cannot encode the selected image"
                    } }.toByteArray()
                } finally { if (cropped !== bitmap) cropped.recycle() }
            }
            authoring.save(storageId, name, size, area, png).storageId
        }
    }

    /** Releases projection before another mode can acquire the lease. Example: `driver.close()`. */
    suspend fun close() = mutex.withLock {
        closed = true
        orientationListener?.let(display::removeOrientationListener)
        orientationListener = null
        recorder.stopProjection()
    }
}
