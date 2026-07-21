/* Copyright (C) 2026 Kevin Buzeau */
package com.buzbuz.smartautoclicker.feature.dumb.config.ui.actions.zoom

import android.content.Context
import android.graphics.Point
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbAction
import com.buzbuz.smartautoclicker.feature.dumb.config.data.getDumbConfigPreferences
import com.buzbuz.smartautoclicker.feature.dumb.config.data.putZoomDurationConfig
import com.buzbuz.smartautoclicker.feature.dumb.config.data.putZoomIntensityConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DumbZoomViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val androidActionExecutor: AndroidActionExecutor,
) : ViewModel() {

    private val editedZoom = MutableStateFlow<DumbAction.DumbZoom?>(null)
    val zoom = editedZoom.filterNotNull()
    val isValid = editedZoom.map { it?.isValid() == true }

    fun setEditedZoom(zoom: DumbAction.DumbZoom) {
        editedZoom.value = zoom.copy(center = Point(zoom.center))
    }

    fun getEditedZoom(): DumbAction.DumbZoom? = editedZoom.value

    fun setName(name: String) = update { copy(name = name) }

    fun setDirection(direction: ZoomDirection) = update { copy(direction = direction) }

    fun setIntensity(intensityPx: Int) = update { copy(intensityPx = intensityPx) }

    fun setDuration(durationMs: Long) = update { copy(zoomDurationMs = durationMs) }

    fun setGeometry(center: Point, intensityPx: Int) = update { copy(center = center, intensityPx = intensityPx) }

    fun testZoom() {
        val zoom = getEditedZoom()?.takeIf(DumbAction.DumbZoom::isValid) ?: return
        viewModelScope.launch {
            androidActionExecutor.dispatchZoomGesture(
                zoom.center,
                zoom.intensityPx,
                zoom.zoomDurationMs,
                zoom.direction,
                null,
            )
        }
    }

    fun saveLastConfig() {
        val zoom = getEditedZoom() ?: return
        context.getDumbConfigPreferences().edit {
            putZoomDurationConfig(zoom.zoomDurationMs)
            putZoomIntensityConfig(zoom.intensityPx)
        }
    }

    private fun update(transform: DumbAction.DumbZoom.() -> DumbAction.DumbZoom) {
        editedZoom.value = editedZoom.value?.transform()
    }
}
