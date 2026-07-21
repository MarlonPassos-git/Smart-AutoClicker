package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.brief.__tests__

import android.content.Context
import android.graphics.Point
import android.os.Build
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.TutorialRepository
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClick
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers.AreaClickDescription
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.EditedListState
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.IEditionState
import com.buzbuz.smartautoclicker.feature.smart.config.domain.usecase.copy.availability.IsActionCopyAvailableUseCase
import com.buzbuz.smartautoclicker.feature.smart.config.ui.action.brief.SmartActionsBriefViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class AreaClickBriefPreviewUnitSpec {

    @Test
    fun `focused area click exposes its configured polygon`() = runTest {
        val vertices = listOf(Point(40, 60), Point(320, 80), Point(260, 280), Point(70, 240))
        val viewModel = createViewModel(areaClick(vertices))

        val description = viewModel.actionVisualization.first() as AreaClickDescription

        assertEquals(vertices.map { point -> point.x.toFloat() to point.y.toFloat() }, description.vertices.map { it.x to it.y })
    }

    private fun createViewModel(areaClick: AreaClick): SmartActionsBriefViewModel {
        val editionState = mockk<IEditionState>()
        val editionRepository = mockk<EditionRepository>()
        val copyAvailability = mockk<IsActionCopyAvailableUseCase>()
        val settingsRepository = mockk<SettingsRepository>()
        every { editionRepository.editionState } returns editionState
        every { editionState.editedEventActionsState } returns flowOf(actionListState(areaClick))
        every { editionState.editedEventState } returns emptyFlow()
        every { copyAvailability() } returns flowOf(false)
        every { settingsRepository.isLegacyActionUiEnabledFlow } returns flowOf(false)
        return SmartActionsBriefViewModel(
            mockk<Context>(relaxed = true), copyAvailability, mockk<BitmapRepository>(), editionRepository,
            mockk<SmartProcessingRepository>(relaxed = true), mockk<MonitoredViewsManager>(relaxed = true),
            mockk<TutorialRepository>(relaxed = true), settingsRepository,
        )
    }

    private fun actionListState(areaClick: AreaClick): EditedListState<Action> =
        EditedListState(listOf(areaClick), listOf(true), hasChanged = false, canBeSaved = true)

    private fun areaClick(vertices: List<Point>) = AreaClick(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Combat area",
        priority = 0,
        vertices = vertices,
    )
}
