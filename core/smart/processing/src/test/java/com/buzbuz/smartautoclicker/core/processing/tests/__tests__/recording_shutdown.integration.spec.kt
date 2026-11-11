package com.buzbuz.smartautoclicker.core.processing.tests

import android.content.Intent
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.processing.data.DetectorEngine
import com.buzbuz.smartautoclicker.core.processing.data.DetectorState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecordingShutdownIntegrationTest {
    @Test
    fun stopDuringPreparationCancelsCaptureValidationAndWaitsForProjectionRelease() = runTest {
        val recorder = mockk<DisplayRecorder>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var validationCancelled = false
        coEvery { recorder.validateScreenCapture() } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { validationCancelled = true }
        }
        coEvery { recorder.stopProjection() } coAnswers { release.await() }
        val engine = engine(recorder, StandardTestDispatcher(testScheduler))
        engine.startScreenRecord(-1, Intent(), null)
        runCurrent()
        assertTrue(entered.isCompleted)
        val first = launch { engine.stopScreenRecordAndWait() }
        val second = launch { engine.stopScreenRecordAndWait() }
        runCurrent()
        assertTrue(validationCancelled)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        engine.startScreenRecord(-1, Intent(), null)
        coVerify(exactly = 1) { recorder.startProjection(any(), any(), any()) }
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(DetectorState.CREATED, engine.state.value)
        coVerify(exactly = 1) { recorder.stopProjection() }
    }

    @Test
    fun failedCaptureIsReleasedBeforeAnotherRecordingStarts() = runTest {
        val recorder = mockk<DisplayRecorder>(relaxed = true)
        coEvery { recorder.validateScreenCapture() } returns false
        val engine = engine(recorder, StandardTestDispatcher(testScheduler))
        engine.startScreenRecord(-1, Intent(), null)
        advanceUntilIdle()
        assertEquals(DetectorState.ERROR_SCREEN_IMAGE_CAPTURE_FAILED, engine.state.value)
        val stop = launch { engine.stopScreenRecordAndWait() }
        advanceUntilIdle()
        assertTrue(stop.isCompleted)
        assertEquals(DetectorState.CREATED, engine.state.value)
        coEvery { recorder.validateScreenCapture() } returns true
        engine.startScreenRecord(-1, Intent(), null)
        advanceUntilIdle()
        assertEquals(DetectorState.RECORDING, engine.state.value)
        val nextStop = launch { engine.stopScreenRecordAndWait() }
        advanceUntilIdle()
        assertTrue(nextStop.isCompleted)
        coVerify(exactly = 2) { recorder.stopProjection() }
    }

    private fun engine(recorder: DisplayRecorder, dispatcher: CoroutineDispatcher): DetectorEngine {
        val display = mockk<DisplayConfigManager>(relaxed = true)
        every { display.displayConfig } returns DisplayConfig(Point(100, 200), 1, 0, emptyMap())
        return DetectorEngine(dispatcher, display, mockk(relaxed = true), mockk(relaxed = true), recorder,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }
}
