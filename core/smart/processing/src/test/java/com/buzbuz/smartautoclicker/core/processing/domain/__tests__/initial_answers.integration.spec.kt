package com.buzbuz.smartautoclicker.core.processing.domain

import android.content.Context
import android.os.PowerManager
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterStartQuestion
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.data.DetectorEngine
import com.buzbuz.smartautoclicker.core.processing.data.DetectorState
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.CountersState
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InitialAnswersIntegrationTest {
    private val id = Identifier(databaseId = 7L)
    private val definition = Counter("target", 50.0, id, CounterStartQuestion("Meta", minimum = 1.0, maximum = 100.0))
    private val engine = mockk<DetectorEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(DetectorState.RECORDING)
    }
    private val context = mockk<Context> {
        every { getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager> {
            every { newWakeLock(any(), any()) } returns mockk(relaxed = true)
        }
    }
    private val source = mockk<IRepository>(relaxed = true) {
        coEvery { getScenario(7L) } returns Scenario(id, "Farm", 1600)
        coEvery { getCounters(7L) } returns listOf(definition)
    }
    private val repository = SmartProcessingRepositoryImpl(context, Dispatchers.Unconfined, Dispatchers.Unconfined, source, engine)

    @Test fun `engine and report receive initial overrides before any counter operation and next run resets`() = runBlocking {
        repository.setScenarioId(id, markAsUsed = false)
        val snapshots = mutableListOf<List<Counter>>()
        every { engine.startDetection(any(), any(), any(), any(), capture(snapshots), any(), any(), any()) } just Runs
        repository.startDetection(context, false, true, null, mapOf("target" to 80.0))
        val state = CountersState(snapshots.single(), null)
        assertEquals(80.0, state.getCounterValue("target")!!, 0.0)
        // The same snapshot reaches DetectorEngine's report recorder as well as processing.
        assertEquals(80.0, snapshots.single().single().defaultValue, 0.0)
        state.setCounterValue("target", 99.0)
        repository.startDetection(context, false, true, null, emptyMap())
        assertEquals(50.0, CountersState(snapshots.last(), null).getCounterValue("target")!!, 0.0)
        assertEquals(50.0, definition.defaultValue, 0.0)
        coVerify(exactly = 0) { source.updateScenario(any(), any(), any()) }
    }

    @Test fun `invalid complete set cannot reach engine`() = runBlocking {
        repository.setScenarioId(id, markAsUsed = false)
        try {
            repository.startDetection(context, false, false, null, mapOf("target" to 101.0))
            fail("Invalid initial answers must be rejected")
        } catch (_: IllegalArgumentException) { }
        verify(exactly = 0) { engine.startDetection(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
