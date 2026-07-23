/*
 * Copyright (C) 2024 Kevin Buzeau
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image

import android.graphics.Bitmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredViewType

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CaptureViewModel @Inject constructor(
    private val displayRecorder: DisplayRecorder,
    private val monitoredViewsManager: MonitoredViewsManager,
) : ViewModel()  {

    fun takeScreenshot(resultCallback: (Bitmap?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            delay(200L)
            val screenshot = displayRecorder.takeScreenshot()

            withContext(Dispatchers.Main) {
                resultCallback(screenshot)
                if (screenshot != null) {
                    monitoredViewsManager.notifyClick(MonitoredViewType.SCREEN_CONDITION_CAPTURE_MENU_BUTTON_CAPTURE)
                }
            }
        }
    }

}
