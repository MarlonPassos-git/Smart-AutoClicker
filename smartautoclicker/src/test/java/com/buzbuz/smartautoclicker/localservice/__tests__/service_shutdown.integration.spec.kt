package com.buzbuz.smartautoclicker.localservice

import android.app.Application
import android.app.Notification
import java.io.StringWriter
import java.io.PrintWriter
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostics
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionStatus
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.buzbuz.smartautoclicker.feature.notifications.ServiceNotificationController
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import android.content.Intent
import android.content.ComponentName
import com.buzbuz.smartautoclicker.core.base.data.AppComponentsProvider
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.dumb.engine.DumbEngine
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServiceShutdownIntegrationTest {
    @Test
    fun scriptReportExportsRetainedEventsWithoutEnablingTracingAndEndsWithItsSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val diagnostics = ScriptDiagnostics(2)
        val driver = mockk<ScriptServiceDriver>(relaxed = true)
        every { driver.diagnostics } returns diagnostics
        every { driver.status } returns MutableStateFlow(ScriptSessionStatus(ScriptSessionState.RUNNING))
        mockkConstructor(ServiceNotificationController::class)
        every { anyConstructed<ServiceNotificationController>().createNotification(any(), any(), any(), any()) } returns Notification()
        val fixture = Fixture(script = driver)
        fun report(): String = StringWriter().also { fixture.service.writeScriptDiagnostics(PrintWriter(it)) }.toString()
        try {
            assertEquals("", report())
            assertTrue(fixture.service.startScript(-1, Intent(), "reviewed", "{}"))
            runCurrent()
            for (index in 0..2) diagnostics.record(ScriptDiagnostic("log", "conferencia", "Valor $index", index.toLong()))
            val events = report().lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.toList()
            assertEquals(listOf("Valor 1", "Valor 2"), events.map { it.getValue("message").jsonPrimitive.content })
            assertEquals(listOf("1", "2"), events.map { it.getValue("monotonicNanos").jsonPrimitive.content })
            assertFalse(diagnostics.isDetailedEnabled)
            fixture.service.stopScenario()
            advanceUntilIdle()
            assertEquals("", report())
        } finally {
            fixture.service.stopScenario()
            advanceUntilIdle()
            fixture.service.release()
            unmockkConstructor(ServiceNotificationController::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun calibrationBlocksOtherModesUntilItsProjectionIsReleased() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ownership = SessionOwnership()
        val driver = mockk<ScriptCalibrationDriver>(relaxed = true)
        mockkConstructor(ServiceNotificationController::class)
        every { anyConstructed<ServiceNotificationController>().createNotification(any(), any(), any(), any()) } returns Notification()
        val fixture = Fixture(ownership, calibration = driver)
        val next = Fixture(ownership)
        val release = CompletableDeferred<Unit>()
        coEvery { driver.close() } coAnswers { release.await() }
        try {
            assertTrue(fixture.service.startScriptCalibration(-1, Intent(), "reviewed", "confirm", true))
            runCurrent()
            assertEquals(listOf(-1L), fixture.started)
            fixture.service.startDumbScenario(scenario(1))
            assertEquals(listOf(-1L), fixture.started)
            fixture.service.stopScenario()
            fixture.service.release()
            runCurrent()
            next.service.startDumbScenario(scenario(2))
            assertTrue(next.started.isEmpty())
            release.complete(Unit)
            runCurrent()
            next.service.startDumbScenario(scenario(2))
            assertEquals(listOf(2L), next.started)
            coVerify(exactly = 1) { driver.close() }
        } finally {
            release.complete(Unit)
            next.service.stopScenario()
            advanceUntilIdle()
            next.service.release()
            unmockkConstructor(ServiceNotificationController::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reconnectionWaitsForThePreviousServiceCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ownership = SessionOwnership()
        val previous = Fixture(ownership)
        val replacement = Fixture(ownership)
        val cleanup = CompletableDeferred<Unit>()
        coEvery { previous.smart.stopScreenRecordAndWait() } coAnswers { cleanup.await() }
        try {
            previous.service.startDumbScenario(scenario(1))
            advanceTimeBy(500)
            runCurrent()
            previous.service.stopScenario()
            previous.service.release()
            runCurrent()
            replacement.service.startDumbScenario(scenario(2))
            assertTrue(replacement.started.isEmpty())
            cleanup.complete(Unit)
            runCurrent()
            replacement.service.startDumbScenario(scenario(2))
            assertEquals(listOf(2L), replacement.started)
        } finally {
            cleanup.complete(Unit)
            replacement.service.stopScenario()
            advanceUntilIdle()
            previous.service.release()
            replacement.service.release()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedForegroundStartReleasesTheClaimForAnotherService() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ownership = SessionOwnership()
        val failed = Fixture(ownership, failToStart = true)
        val replacement = Fixture(ownership)
        try {
            var rejected = false
            try { failed.service.startDumbScenario(scenario(1)) } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertFalse(failed.service.isStarted)
            replacement.service.startDumbScenario(scenario(2))
            assertEquals(listOf(2L), replacement.started)
        } finally {
            replacement.service.stopScenario()
            advanceUntilIdle()
            failed.service.release()
            replacement.service.release()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun anOldLeaseCannotReleaseANewerOwner() {
        val ownership = SessionOwnership()
        val previous = checkNotNull(ownership.tryAcquire())
        previous.close()
        val next = checkNotNull(ownership.tryAcquire())
        previous.close()
        assertTrue(ownership.tryAcquire() == null)
        next.close()
        checkNotNull(ownership.tryAcquire()).close()
    }

    @Test
    fun anotherModeCannotStartUntilNativeCleanupCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val cleanup = CompletableDeferred<Unit>()
        coEvery { fixture.smart.stopScreenRecordAndWait() } coAnswers { cleanup.await() }
        try {
            fixture.service.startDumbScenario(scenario(1))
            advanceTimeBy(500)
            runCurrent()
            fixture.service.stopScenario()
            runCurrent()
            assertTrue(fixture.service.isStarted)
            fixture.service.stopScenario()
            fixture.service.startDumbScenario(scenario(2))
            assertEquals(listOf(1L), fixture.started)
            assertEquals(0, fixture.stopped)
            coVerify(exactly = 1) { fixture.smart.stopScreenRecordAndWait() }
            cleanup.complete(Unit)
            runCurrent()
            assertFalse(fixture.service.isStarted)
            assertEquals(1, fixture.stopped)
            fixture.service.startDumbScenario(scenario(2))
            assertEquals(listOf(1L, 2L), fixture.started)
            fixture.service.stopScenario()
            advanceUntilIdle()
        } finally {
            fixture.service.release()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun accessibilityUnbindDoesNotCancelThePendingCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val cleanup = CompletableDeferred<Unit>()
        coEvery { fixture.smart.stopScreenRecordAndWait() } coAnswers { cleanup.await() }
        try {
            fixture.service.startDumbScenario(scenario(1))
            advanceTimeBy(500)
            runCurrent()
            fixture.service.stopScenario()
            fixture.service.release()
            runCurrent()
            assertTrue(fixture.service.isStarted)
            assertEquals(0, fixture.stopped)
            cleanup.complete(Unit)
            runCurrent()
            assertFalse(fixture.service.isStarted)
            assertEquals(1, fixture.stopped)
        } finally {
            cleanup.complete(Unit)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private fun scenario(id: Long): DumbScenario = mockk(relaxed = true) {
        every { this@mockk.id } returns Identifier(databaseId = id)
    }

    private class Fixture(ownership: SessionOwnership = SessionOwnership(), failToStart: Boolean = false, calibration: ScriptCalibrationDriver? = null, script: ScriptServiceDriver? = null) {
        val started = mutableListOf<Long>()
        var stopped = 0
        val smart = mockk<SmartProcessingRepository>(relaxed = true) {
            every { detectionState } returns MutableStateFlow(DetectionState.INACTIVE)
        }
        val dumb = mockk<DumbEngine>(relaxed = true) {
            every { isRunning } returns MutableStateFlow(false)
        }
        val overlays = mockk<OverlayManager>(relaxed = true) {
            every { isStackHidden } returns MutableStateFlow(false)
        }
        val components = mockk<AppComponentsProvider>(relaxed = true) {
            every { scenarioActivityComponentName } returns ComponentName("com.buzbuz.smartautoclicker.debug", "com.buzbuz.smartautoclicker.scenarios.ScenarioActivity")
        }
        val service = LocalService(ApplicationProvider.getApplicationContext(), overlays,
            components, mockk(relaxed = true), smart, dumb, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true),
            onStart = { id, _, _ ->
                if (failToStart) throw IllegalStateException("Foreground start rejected")
                started.add(id)
            }, onStop = { stopped++ }, ownership = ownership, createCalibrationDriver = calibration?.let { { it } }, createScriptDriver = script?.let { { it } })
    }
}
