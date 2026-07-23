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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager

import com.buzbuz.smartautoclicker.core.domain.model.DetectionType
import com.buzbuz.smartautoclicker.core.domain.model.IN_AREA
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.condition.IMAGE_REFERENCES_LIMIT
import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredViewType
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.ImageReferenceEditor
import com.buzbuz.smartautoclicker.feature.smart.config.ui.common.RecoverableOverlayResultQueue
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image.importer.ImageImportCoordinator
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image.importer.ImageImportResult
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ImageConditionViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val bitmapRepository: BitmapRepository,
    private val editionRepository: EditionRepository,
    private val monitoredViewsManager: MonitoredViewsManager,
    private val displayConfigManager: DisplayConfigManager,
    private val imageImportCoordinator: ImageImportCoordinator,
) : ViewModel() {

    /** The condition being configured by the user. */
    private val configuredCondition = editionRepository.editionState.editedScreenConditionState
        .mapNotNull { it.value }
        .filterIsInstance<ScreenCondition.Image>()

    private val editedConditionHasChanged: StateFlow<Boolean> =
        editionRepository.editionState.editedScreenConditionState
            .map { it.hasChanged }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Tells if the user is currently editing a condition. If that's not the case, dialog should be closed. */
    val isEditingCondition: Flow<Boolean> = editionRepository.isEditingCondition
        .distinctUntilChanged()
        .debounce(1000)

    /** The type of detection currently selected by the user. */
    val name: Flow<String?> = configuredCondition.map { it.name }.take(1)
    /** Tells if the condition name is valid or not. */
    val nameError: Flow<Boolean> = configuredCondition.map { it.name.isEmpty() }

    /** Tells if the condition should be present or not on the screen. */
    val shouldBeDetected: Flow<Boolean> = configuredCondition
        .map { condition -> condition.shouldBeDetected }

    /** The type of detection currently selected by the user. */
    val detectionType: Flow<DetectionTypeState> = configuredCondition
        .map { condition ->
            context.getDetectionTypeState(condition.detectionType, condition.detectionArea ?: condition.area)
        }
        .filterNotNull()

    /** The condition threshold value currently edited by the user. */
    val threshold: Flow<Int> = configuredCondition.mapNotNull { it.threshold }
    val imageReferences: Flow<ImageReferencesState> = configuredCondition.mapLatest { condition ->
        ImageReferencesState(
            items = condition.references.mapIndexed { index, reference ->
                ImageReferenceItem(
                    index = index,
                    reference = reference,
                    bitmap = bitmapRepository.getImageConditionBitmap(
                        reference.path,
                        reference.area.width(),
                        reference.area.height(),
                    ),
                )
            },
            canAdd = condition.references.size < IMAGE_REFERENCES_LIMIT,
        )
    }.flowOn(Dispatchers.IO)

    private val imageImportResultQueue = RecoverableOverlayResultQueue<PendingImageImport>()
    val imageImportResults: Flow<PendingImageImport> = imageImportResultQueue.results
    private val referenceSaveFailureQueue = RecoverableOverlayResultQueue<PendingReferenceSave>()
    val referenceSaveFailures: Flow<PendingReferenceSave> = referenceSaveFailureQueue.results
    /** Tells if the configured condition is valid and can be saved. */
    val conditionCanBeSaved: Flow<Boolean> = editionRepository.editionState.editedScreenConditionState.map { condition ->
        condition.canBeSaved
    }

    fun hasUnsavedModifications(): Boolean =
        editedConditionHasChanged.value

    /**
     * Set the configured condition name.
     * @param name the new condition name.
     */
    fun setName(name: String) {
        updateEditedCondition { it.copy(name = name) }
    }

    /** Set the shouldBeDetected value of the condition. */
    fun toggleShouldBeDetected() {
        updateEditedCondition { oldCondition ->
            oldCondition.copy(shouldBeDetected = !oldCondition.shouldBeDetected)
        }
    }

    /** Set the detection type. */
    fun setDetectionType(newType: Int) {
        updateEditedCondition { oldCondition ->
            val detectionArea =
                if (oldCondition.detectionArea == null && newType == IN_AREA) oldCondition.area
                else oldCondition.detectionArea

            ImageReferenceEditor.expandDetectionArea(
                oldCondition.copy(detectionType = newType, detectionArea = detectionArea),
                displayConfigManager.displayConfig.sizePx,
            )
        }
    }

    /** Set the area to detect in. */
    fun setDetectionArea(area: Rect) {
        updateEditedCondition { oldCondition ->
            ImageReferenceEditor.expandDetectionArea(
                oldCondition.copy(detectionArea = Rect(area)),
                displayConfigManager.displayConfig.sizePx,
            )
        }
    }

    /**
     * Set the threshold of the configured condition.
     * @param value the new threshold value.
     */
    fun setThreshold(value: Int) {
        updateEditedCondition { oldCondition ->
            oldCondition.copy(threshold = value)
        }
    }

    fun isConditionRelatedToClick(): Boolean =
        editionRepository.editionState.isEditedConditionReferencedByClick()

    fun saveReference(area: Rect, bitmap: Bitmap, replacementIndex: Int?) {
        val pendingSave = PendingReferenceSave(Rect(area), bitmap, replacementIndex)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reference = editionRepository.editedItemsBuilder.createImageReference(area, bitmap)
                withContext(Dispatchers.Main) { applyReference(reference, replacementIndex) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                referenceSaveFailureQueue.enqueue(pendingSave)
            }
        }
    }

    fun removeReference(index: Int) {
        updateEditedCondition { condition -> ImageReferenceEditor.remove(condition, index) }
    }

    fun moveReference(from: Int, to: Int) {
        updateEditedCondition { condition -> ImageReferenceEditor.move(condition, from, to) }
    }

    fun reorderReferences(references: List<ImageReference>) {
        updateEditedCondition { condition -> ImageReferenceEditor.reorder(condition, references) }
    }

    fun requestImageImport(context: Context, replacementIndex: Int?) {
        viewModelScope.launch {
            val result = imageImportCoordinator.requestImage(context)
            imageImportResultQueue.enqueue(PendingImageImport(replacementIndex, result))
        }
    }

    fun centerImageArea(bitmap: Bitmap): Rect =
        ImageReferenceEditor.centerArea(bitmap.width, bitmap.height, displayConfigManager.displayConfig.sizePx)

    fun fixImagePosition(position: Rect, imageArea: Rect): Rect =
        ImageReferenceEditor.positionWithFixedSize(position, imageArea, displayConfigManager.displayConfig.sizePx)


    fun monitorSaveButtonView(view: View) {
        monitoredViewsManager.attach(MonitoredViewType.SCREEN_CONDITION_DIALOG_BUTTON_SAVE, view)
    }

    fun monitorDetectionTypeItemInAreaView(view: View) {
        monitoredViewsManager.attach(MonitoredViewType.SCREEN_CONDITION_DIALOG_FIELD_TYPE_ITEM_IN_AREA, view)
    }

    fun monitorDetectionAreaSelector(view: View) {
        monitoredViewsManager.attach(MonitoredViewType.SCREEN_CONDITION_DIALOG_FIELD_AREA_SELECTOR, view)
    }

    fun monitorVisibilityView(view: View) {
        monitoredViewsManager.attach(MonitoredViewType.SCREEN_CONDITION_DIALOG_FIELD_VISIBILITY, view)
    }

    fun stopViewMonitoring() {
        monitoredViewsManager.detach(MonitoredViewType.SCREEN_CONDITION_DIALOG_BUTTON_SAVE)
        monitoredViewsManager.detach(MonitoredViewType.SCREEN_CONDITION_DIALOG_FIELD_TYPE_ITEM_IN_AREA)
        monitoredViewsManager.detach(MonitoredViewType.SCREEN_CONDITION_DIALOG_FIELD_AREA_SELECTOR)
        monitoredViewsManager.detach(MonitoredViewType.SCREEN_CONDITION_DIALOG_FIELD_VISIBILITY)
    }

    private fun applyReference(reference: ImageReference, replacementIndex: Int?) {
        updateEditedCondition { condition ->
            if (replacementIndex == null) {
                ImageReferenceEditor.add(condition, reference, displayConfigManager.displayConfig.sizePx)
            } else {
                ImageReferenceEditor.replace(condition, replacementIndex, reference, displayConfigManager.displayConfig.sizePx)
            }
        }
    }

    private fun updateEditedCondition(closure: (oldValue: ScreenCondition.Image) -> ScreenCondition.Image?) {
        editionRepository.editionState.getEditedCondition<ScreenCondition.Image>()?.let { condition ->
            closure(condition)?.let { newValue ->
                editionRepository.updateEditedCondition(newValue)
            }
        }
    }

    private fun Context.getDetectionTypeState(@DetectionType type: Int, area: Rect) = DetectionTypeState(
        type = type,
        areaText = getString(R.string.field_select_detection_area_desc, area.left, area.top, area.right, area.bottom)
    )
}

data class DetectionTypeState(
    @param:DetectionType val type: Int,
    val areaText: String,
)

data class ImageReferenceItem(
    val index: Int,
    val reference: ImageReference,
    val bitmap: Bitmap?,
)

data class ImageReferencesState(
    val items: List<ImageReferenceItem>,
    val canAdd: Boolean,
)

data class PendingImageImport(
    val replacementIndex: Int?,
    val result: ImageImportResult,
)

data class PendingReferenceSave(
    val area: Rect,
    val bitmap: Bitmap,
    val replacementIndex: Int?,
)

/** The maximum threshold value selectable by the user. */
const val MAX_THRESHOLD = 20f
