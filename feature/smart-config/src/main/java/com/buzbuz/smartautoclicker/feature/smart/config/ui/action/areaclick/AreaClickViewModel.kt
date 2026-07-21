package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick

import android.content.Context
import android.graphics.Point
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClick
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getEventConfigPreferences
import com.buzbuz.smartautoclicker.feature.smart.config.utils.putAreaClickConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

class AreaClickViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val editionRepository: EditionRepository,
) : ViewModel() {
    private val preferences = context.getEventConfigPreferences()
    private val configuredAreaClick = editionRepository.editionState.editedActionState
        .mapNotNull { it.value }.filterIsInstance<AreaClick>()

    val uiState: StateFlow<AreaClickUiState?> = configuredAreaClick.map { areaClick ->
        AreaClickUiState(
            canBeSaved = areaClick.isComplete(),
            name = areaClick.name,
            clickCount = areaClick.clickCount.toString(),
            pressDuration = areaClick.pressDurationMs.toString(),
            interval = areaClick.intervalMs.toString(),
            distribution = areaClick.distribution,
            verticesCount = areaClick.vertices.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun getEditedAreaClick(): AreaClick? = editionRepository.editionState.getEditedAction()
    fun setName(value: String) = update { copy(name = value) }
    fun setClickCount(value: Int?) = value?.let { count -> update { copy(clickCount = count) } }
    fun setPressDuration(value: Long?) = value?.let { duration -> update { copy(pressDurationMs = duration) } }
    fun setInterval(value: Long?) = value?.let { interval -> update { copy(intervalMs = interval) } }
    fun setDistribution(value: AreaClickDistribution) = update { copy(distribution = value) }
    fun setVertices(value: List<Point>) = update { copy(vertices = value.map(::Point)) }
    fun saveLastConfig() = getEditedAreaClick()?.let { preferences.edit { putAreaClickConfig(it) } }

    private fun update(transform: AreaClick.() -> AreaClick) {
        getEditedAreaClick()?.let { editionRepository.updateEditedAction(it.transform()) }
    }
}
