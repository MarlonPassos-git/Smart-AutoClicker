package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.Bitmap
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidScreenBackendIntegrationTest {
    @Test(timeout = 10000)
    fun stoppingASequenceWaitsForTheAndroidGestureBeforeReleasingItsPointerAndSkipsRemainingTaps() = runBlocking {
        val actions = mockk<AndroidActionExecutor>(relaxed = true)
        every { actions.getHeldPointer() } returns null
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var pointerDown = false
        coEvery { actions.dispatchBuiltGesture(any()) } coAnswers {
            entered.complete(Unit)
            finished.await()
            pointerDown = true
        }
        coEvery { actions.releaseHeldPointer() } coAnswers { pointerDown = false }
        val backend = AndroidScreenAutomationBackend(mockk(relaxed = true), actions, { null }, { error("Unexpected resource") }, "unit-processing-tag")
        val running = launch {
            try {
                backend.tapSequence(listOf(ScreenTapStep(ScreenPoint(10, 20), 20, 0), ScreenTapStep(ScreenPoint(30, 40), 20, 0)))
            } finally {
                backend.releasePointers()
            }
        }
        withTimeout(5000) { entered.await() }
        running.cancel()
        yield()
        assertFalse("The previous gesture still owns the Android resource", running.isCompleted)
        coVerify(exactly = 0) { actions.releaseHeldPointer() }
        finished.complete(Unit)
        running.join()
        assertFalse("Cleanup must run after Android has recorded the held pointer", pointerDown)
        coVerify(exactly = 1) { actions.releaseHeldPointer() }
        coVerify(exactly = 1) { actions.dispatchBuiltGesture(any()) }
    }

    @Test
    fun zoomAndHeldMultitouchUseTheExistingExecutor() = runBlocking {
        val actions = mockk<AndroidActionExecutor>(relaxed = true)
        every { actions.getHeldPointer() } returns null
        val backend = AndroidScreenAutomationBackend(mockk(relaxed = true), actions, { null }, { error("Unexpected resource") }, "unit-processing-tag")
        backend.zoom(ScreenPoint(100, 200), 50, 300, ScreenZoomDirection.OUT)
        backend.gesture(listOf(ScreenGestureFinger(ScreenPoint(20, 30), 0, 100, emptyList(), true)))
        backend.releasePointers()
        coVerify(exactly = 1) { actions.dispatchZoomGesture(android.graphics.Point(100, 200), 50, 300,
            com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection.OUT, null) }
        coVerify(exactly = 1) { actions.dispatchBuiltGesture(match { it.heldPointer != null }) }
        coVerify(exactly = 1) { actions.releaseHeldPointer() }
    }

    @Test
    @Config(sdk = [24])
    fun api24RejectsUnsupportedPointerContinuationBeforeDispatch() = runBlocking {
        val actions = mockk<AndroidActionExecutor>(relaxed = true)
        val backend = AndroidScreenAutomationBackend(mockk(relaxed = true), actions, { null }, { error("Unexpected resource") }, "unit-processing-tag")
        var rejected = false
        try { backend.gesture(listOf(ScreenGestureFinger(ScreenPoint(20, 30), 0, 100, emptyList(), true))) }
        catch (_: IllegalArgumentException) { rejected = true }
        org.junit.Assert.assertTrue(rejected)
        coVerify(exactly = 0) { actions.dispatchBuiltGesture(any()) }
    }

    @Test
    fun readsShareTheCapturedBitmapAndInvalidationReleasesWithoutRecyclingIt() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val detector = mockk<ImageDetector>(relaxed = true)
        every { detector.detectNumber(any(), any(), any()) } returns DetectionResult(isDetected = true, numberDetected = 0.0)
        val backend = AndroidScreenAutomationBackend(detector, mockk(relaxed = true), { bitmap }, { error("Unexpected reference") }, "unit-processing-tag")
        val query = RecognitionQuery(RecognitionKind.NUMBER, ScreenRegion(0, 0, 50, 50), 80)
        assertEquals(ScreenSize(100, 200), backend.capture())
        assertEquals(0.0, backend.recognize(query).number)
        assertEquals(RecognitionStatus.FOUND, backend.recognize(query).status)
        backend.invalidateObservation()
        backend.invalidateObservation()
        verify(exactly = 1) { detector.setScreenBitmap(bitmap, "unit-processing-tag") }
        verify(exactly = 1) { detector.releaseScreenBitmap(bitmap) }
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun failedNativeNumberRecognitionRemainsAnInvalidReading() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val detector = mockk<ImageDetector>(relaxed = true)
        every { detector.detectNumber(any(), any(), any()) } returns DetectionResult(isDetected = false)
        val backend = AndroidScreenAutomationBackend(detector, mockk(relaxed = true), { bitmap }, { error("Unexpected reference") }, "unit-processing-tag")
        backend.capture()
        assertEquals(RecognitionStatus.INVALID_READING,
            backend.recognize(RecognitionQuery(RecognitionKind.NUMBER, ScreenRegion(0, 0, 50, 50), 80)).status)
        backend.invalidateObservation()
        bitmap.recycle()
    }

    @Test
    fun tapsUseTheExistingGestureBuilderAndAccessibilityExecutor() = runBlocking {
        val actions = mockk<AndroidActionExecutor>(relaxed = true)
        every { actions.getHeldPointer() } returns null
        val backend = AndroidScreenAutomationBackend(mockk(relaxed = true), actions, { null }, { error("Unexpected reference") }, "unit-processing-tag")
        backend.tap(ScreenPoint(20, 30), 50)
        backend.releasePointers()
        coVerify(exactly = 1) { actions.dispatchBuiltGesture(any()) }
        coVerify(exactly = 1) { actions.releaseHeldPointer() }
    }
}
