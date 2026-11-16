package com.buzbuz.smartautoclicker.core.processing.data.processor

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingPauseTest {
    @Test fun pauseFreezesWaitAndClockAndReleasesOnceWithoutLosingContinuation() = runTest {
        val pause = ProcessingPause { testScheduler.currentTime }
        var releases = 0
        var effects = 0
        pause.onPause = { releases++ }
        val job = launch { pause.wait(1000); effects++ }
        advanceTimeBy(200)
        pause.pause()
        runCurrent()
        advanceTimeBy(10000)
        assertEquals(200L, pause.now())
        assertEquals(1, releases)
        assertEquals(0, effects)
        pause.resume()
        advanceTimeBy(799)
        assertEquals(0, effects)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, effects)
        job.join()
    }

    @Test fun stopDuringPauseCancelsPendingEffectsAndRapidResumeStillInvalidates() = runTest {
        val pause = ProcessingPause { testScheduler.currentTime }
        var effects = 0
        var invalidations = 0
        pause.onPause = { invalidations++ }
        pause.pause()
        val job = launch { pause.checkpoint(); effects++ }
        runCurrent()
        job.cancelAndJoin()
        pause.resume()
        assertEquals(0, effects)
        pause.pause()
        pause.resume()
        pause.checkpoint()
        assertEquals(2, invalidations)
        assertEquals(2L, pause.observedGeneration)
    }
}
