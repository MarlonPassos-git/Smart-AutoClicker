/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image

import android.view.View

import androidx.lifecycle.ViewModel

import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredViewType

import javax.inject.Inject

class ImageSourceSelectionViewModel @Inject constructor(
    private val monitoredViewsManager: MonitoredViewsManager,
) : ViewModel() {

    fun updateCaptureChoiceView(view: View?) {
        if (view == null) {
            stopCaptureChoiceMonitoring()
            return
        }

        monitoredViewsManager.attach(MonitoredViewType.IMAGE_SOURCE_SELECTION_CAPTURE, view)
    }

    fun stopCaptureChoiceMonitoring() {
        monitoredViewsManager.detach(MonitoredViewType.IMAGE_SOURCE_SELECTION_CAPTURE)
    }
}
