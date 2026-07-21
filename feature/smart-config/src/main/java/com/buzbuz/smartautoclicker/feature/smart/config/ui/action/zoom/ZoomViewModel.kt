/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.zoom

import android.graphics.Point
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.domain.model.action.Zoom
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getEventConfigPreferences
import com.buzbuz.smartautoclicker.feature.smart.config.utils.putZoomDurationConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.putZoomIntensityConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ZoomViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val editionRepository: EditionRepository,
    private val androidActionExecutor: AndroidActionExecutor,
) : ViewModel() {

    private val configuredZoom = editionRepository.editionState.editedActionState
        .mapNotNull { it.value }
        .filterIsInstance<Zoom>()

    val uiState: StateFlow<ZoomUiState?> = combine(
        configuredZoom,
        editionRepository.editionState.editedActionState,
    ) { zoom, actionState -> zoom.toUiState(actionState.hasChanged, actionState.canBeSaved) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun getEditedZoom(): Zoom? = editionRepository.editionState.getEditedAction()

    fun setName(name: String) = updateZoom { copy(name = name) }

    fun setDirection(direction: ZoomDirection) = updateZoom { copy(direction = direction) }

    fun setIntensity(intensityPx: Int?) = updateZoom { copy(intensityPx = intensityPx) }

    fun setDuration(durationMs: Long?) = updateZoom { copy(zoomDurationMs = durationMs) }

    fun setGeometry(center: Point, intensityPx: Int) =
        updateZoom { copy(center = center, intensityPx = intensityPx) }

    fun testZoom() {
        val zoom = getEditedZoom()?.takeIf(Zoom::isComplete) ?: return
        viewModelScope.launch {
            androidActionExecutor.dispatchZoomGesture(
                zoom.center!!,
                zoom.intensityPx!!,
                zoom.zoomDurationMs!!,
                zoom.direction,
                null,
            )
        }
    }

    fun saveLastConfig() {
        val zoom = getEditedZoom() ?: return
        val durationMs = zoom.zoomDurationMs ?: return
        val intensityPx = zoom.intensityPx ?: return
        context.getEventConfigPreferences().edit {
            putZoomDurationConfig(durationMs)
            putZoomIntensityConfig(intensityPx)
        }
    }

    private fun updateZoom(transform: Zoom.() -> Zoom) {
        getEditedZoom()?.let { editionRepository.updateEditedAction(it.transform()) }
    }

    private fun Zoom.toUiState(hasChanged: Boolean, canSave: Boolean) = ZoomUiState(
        canBeSaved = canSave,
        hasUnsavedModifications = hasChanged,
        name = name,
        nameError = name.isNullOrEmpty(),
        direction = direction,
        intensity = intensityPx?.toString(),
        intensityError = (intensityPx ?: 0) <= 0,
        duration = zoomDurationMs?.toString(),
        durationError = (zoomDurationMs ?: 0) <= 0,
        geometryDescription = center?.let { "(${it.x}, ${it.y}) · ${intensityPx ?: 0}px" },
        geometryError = center == null,
    )
}

data class ZoomUiState(
    val canBeSaved: Boolean,
    val hasUnsavedModifications: Boolean,
    val name: String?,
    val nameError: Boolean,
    val direction: ZoomDirection,
    val intensity: String?,
    val intensityError: Boolean,
    val duration: String?,
    val durationError: Boolean,
    val geometryDescription: String?,
    val geometryError: Boolean,
)
