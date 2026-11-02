package com.buzbuz.smartautoclicker.core.processing.data.processor.__tests__

import android.graphics.Point
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.domain.model.OR
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClick
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickGeometry
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.processor.actions.ActionExecutor
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.mockito.kotlin.argumentCaptor
import com.buzbuz.smartautoclicker.core.common.actions.model.BuiltGesture
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.robolectric.annotation.Config
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class AreaClickExecutorTest {
    private val androidExecutor = mock<AndroidActionExecutor>()
    private val processingState = mock<ProcessingState>()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun oneClickHasNoTrailingInterval() = runTest {
        executor().executeActions(event(clickCount = 1, intervalMs = 200))

        verify(androidExecutor).dispatchBuiltGesture(any())
        assertEquals(0, currentTime)
    }

    @Test
    fun fiftyClicksHaveExactlyFortyNineIntervals() = runTest {
        executor().executeActions(event(clickCount = 50, intervalMs = 10))

        verify(androidExecutor, times(50)).dispatchBuiltGesture(any())
        assertEquals(490, currentTime)
    }

    @Test
    fun cancellationStopsRemainingSequence() = runTest {
        val execution = launch { executor().executeActions(event(clickCount = 50, intervalMs = 100)) }
        testScheduler.advanceTimeBy(250)
        execution.cancelAndJoin()

        verify(androidExecutor, times(3)).dispatchBuiltGesture(any())
    }

    @Test
    fun randomizationNeverMovesSampledTapsOutsideThePolygon() = runTest {
        val original = event(clickCount = 50, intervalMs = 0)
        val polygon = listOf(Point(100, 100), Point(103, 100), Point(103, 150), Point(100, 150))
        val action = (original.actions.single() as AreaClick).copy(vertices = polygon)

        executor(randomize = true).executeActions(original.copy(actions = listOf(action)))

        val gestures = argumentCaptor<BuiltGesture>()
        verify(androidExecutor, times(50)).dispatchBuiltGesture(gestures.capture())
        gestures.allValues.forEach { gesture ->
            val point = shadowOf(gesture.chains.first().first().getStroke(0).path).points.first()
            assertTrue(AreaClickGeometry.containsStrictly(polygon, Point(point.x.toInt(), point.y.toInt())))
        }
    }

    private fun executor(randomize: Boolean = false) = ActionExecutor(
        androidExecutor = androidExecutor,
        processingState = processingState,
        randomize = randomize,
        currentTimeMs = { 4L },
        areaClickRandom = Random(4),
    )

    private fun event(clickCount: Int, intervalMs: Long): ScreenEvent {
        val eventId = Identifier(databaseId = 10)
        val action = AreaClick(
            id = Identifier(databaseId = 11), eventId = eventId, name = "Area", priority = 0,
            vertices = listOf(Point(10, 10), Point(300, 10), Point(300, 300), Point(10, 300)),
            clickCount = clickCount, distribution = AreaClickDistribution.RANDOM,
            pressDurationMs = 20, intervalMs = intervalMs,
        )
        return ScreenEvent(
            id = eventId, scenarioId = Identifier(databaseId = 12), name = "Event", conditionOperator = OR,
            actions = listOf(action), conditions = emptyList(), enabledOnStart = true,
            priority = 0, keepDetecting = false, cooldownMs = 0,
        )
    }
}
