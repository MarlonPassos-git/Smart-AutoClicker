/*
 * Copyright (C) 2026 Kevin Buzeau
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

import android.view.View

import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.implementation.DialogChoice
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.implementation.MultiChoiceDialog
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredOverlayType
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint

sealed class ImageSourceChoice(title: Int, description: Int, icon: Int) : DialogChoice(title, description, icon) {
    data object CaptureScreen : ImageSourceChoice(
        R.string.image_source_capture_title,
        R.string.image_source_capture_description,
        R.drawable.ic_capture,
    )
    data object ChooseFile : ImageSourceChoice(
        R.string.image_source_file_title,
        R.string.image_source_file_description,
        R.drawable.ic_image_condition,
    )
}

class ImageSourceSelectionDialog(
    onChoiceSelected: (ImageSourceChoice) -> Unit,
) : MultiChoiceDialog<ImageSourceChoice>(
    theme = R.style.ScenarioConfigTheme,
    dialogTitleText = R.string.image_source_dialog_title,
    choices = listOf(ImageSourceChoice.CaptureScreen, ImageSourceChoice.ChooseFile),
    onChoiceSelected = onChoiceSelected,
) {
    private val viewModel: ImageSourceSelectionViewModel by viewModels(
        entryPoint = ScenarioConfigViewModelsEntryPoint::class.java,
        creator = { imageSourceSelectionViewModel() },
    )

    override fun tutorialMonitoringTag(): String = MonitoredOverlayType.IMAGE_SOURCE_SELECTION.name

    override fun onChoiceViewBound(choice: ImageSourceChoice, view: View?) {
        if (choice == ImageSourceChoice.CaptureScreen) viewModel.updateCaptureChoiceView(view)
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopCaptureChoiceMonitoring()
    }
}

sealed class ImportedImageUsageChoice(title: Int, description: Int) : DialogChoice(title, description) {
    data object WholeImage : ImportedImageUsageChoice(
        R.string.imported_image_whole_title,
        R.string.imported_image_whole_description,
    )
    data object CropImage : ImportedImageUsageChoice(
        R.string.imported_image_crop_title,
        R.string.imported_image_crop_description,
    )
}

class ImportedImageUsageDialog(
    onChoiceSelected: (ImportedImageUsageChoice) -> Unit,
) : MultiChoiceDialog<ImportedImageUsageChoice>(
    theme = R.style.ScenarioConfigTheme,
    dialogTitleText = R.string.imported_image_usage_dialog_title,
    choices = listOf(ImportedImageUsageChoice.WholeImage, ImportedImageUsageChoice.CropImage),
    onChoiceSelected = onChoiceSelected,
)
