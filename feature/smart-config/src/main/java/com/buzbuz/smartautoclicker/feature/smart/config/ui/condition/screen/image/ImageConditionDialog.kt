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
import android.graphics.Rect
import android.text.InputFilter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper

import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.IN_AREA
import com.buzbuz.smartautoclicker.core.domain.model.WHOLE_SCREEN
import com.buzbuz.smartautoclicker.core.domain.model.condition.IMAGE_REFERENCES_LIMIT
import com.buzbuz.smartautoclicker.core.ui.bindings.buttons.MultiStateButtonConfig
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.DialogNavigationButton
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.setButtonEnabledState
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setButtonConfig
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setChecked
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setDescription
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setEnabled
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setError
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setLabel
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnCheckedListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnClickListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnTextChangedListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnValueChangedFromUserListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setSliderRange
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setSliderValue
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setText
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setTitle
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setValueLabelState
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setupDescriptions
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.DialogConfigConditionImageBinding
import com.buzbuz.smartautoclicker.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.config.ui.common.dialogs.showCloseWithoutSavingDialog
import com.buzbuz.smartautoclicker.feature.smart.config.ui.common.dialogs.showDeleteConditionsWithAssociatedActionsDialog
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.OnConditionConfigCompleteListener
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.areaselector.ConditionAreaSelectorMenu
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image.importer.ImageImportResult

import com.google.android.material.bottomsheet.BottomSheetDialog

import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredOverlayType

class ImageConditionDialog(
    private val listener: OnConditionConfigCompleteListener,
) : OverlayDialog(R.style.ScenarioConfigTheme) {

    override fun tutorialMonitoringTag(): String = MonitoredOverlayType.IMAGE_CONDITION.name

    /** The view model for this dialog. */
    private val viewModel: ImageConditionViewModel by viewModels(
        entryPoint = ScenarioConfigViewModelsEntryPoint::class.java,
        creator = { imageConditionViewModel() },
    )

    /** ViewBinding containing the views for this dialog. */
    private lateinit var viewBinding: DialogConfigConditionImageBinding
    private lateinit var imageReferenceAdapter: ImageReferenceAdapter
    private val referenceTouchHelper = ItemTouchHelper(ImageReferenceReorderTouchHelper())
    private var currentDetectionType = EXACT

    override fun onCreateView(): ViewGroup {
        imageReferenceAdapter = createImageReferenceAdapter()
        viewBinding = DialogConfigConditionImageBinding.inflate(LayoutInflater.from(context))
        configureTopBar()
        configureNameField()
        configureReferenceList()
        configureVisibilityField()
        configureDetectionTypeField()
        configureDetectionAreaField()
        configureThresholdField()

        return viewBinding.root
    }

    private fun configureTopBar() = viewBinding.layoutTopBar.apply {
        dialogTitle.setText(R.string.dialog_title_condition_config)
        buttonDismiss.setDebouncedOnClickListener { back() }
        buttonSave.apply {
            visibility = View.VISIBLE
            setDebouncedOnClickListener {
                listener.onConfirmClicked()
                super.back()
            }
        }
        buttonDelete.apply {
            visibility = View.VISIBLE
            setDebouncedOnClickListener { onDeleteClicked() }
        }
    }

    private fun configureNameField() = viewBinding.fieldEditName.apply {
        setLabel(R.string.generic_name)
        setOnTextChangedListener { viewModel.setName(it.toString()) }
        textField.filters = arrayOf(
            InputFilter.LengthFilter(context.resources.getInteger(R.integer.name_max_length)),
        )
        hideSoftInputOnFocusLoss(textField)
    }

    private fun configureReferenceList() = viewBinding.apply {
        listImageReferences.adapter = imageReferenceAdapter
        referenceTouchHelper.attachToRecyclerView(listImageReferences)
        buttonAddImageReference.setOnClickListener {
            debounceUserInteraction { showImageSourceSelection(replacementIndex = null) }
        }
    }

    private fun configureVisibilityField() = viewBinding.fieldShouldAppear.apply {
        setTitle(context.getString(R.string.field_condition_visibility_title))
        setupDescriptions(listOf(
            context.getString(R.string.field_condition_visibility_desc_absent),
            context.getString(R.string.field_condition_visibility_desc_present),
        ))
        setOnClickListener { viewModel.toggleShouldBeDetected() }
    }

    private fun configureDetectionTypeField() = viewBinding.fieldDetectionType.apply {
        setTitle(context.getString(R.string.field_detection_type_title))
        setButtonConfig(MultiStateButtonConfig(
            icons = listOf(
                R.drawable.ic_detect_exact,
                R.drawable.ic_detect_whole_screen,
                R.drawable.ic_detect_in_area,
            ),
            selectionRequired = true,
        ))
        setupDescriptions(listOf(
            context.getString(R.string.field_detection_type_desc_exact),
            context.getString(R.string.field_detection_type_desc_screen),
            context.getString(R.string.field_select_detection_area_title),
        ))
        setOnCheckedListener { index -> viewModel.setDetectionType(index.fromIndexToDetectionType()) }
    }

    private fun configureDetectionAreaField() = viewBinding.fieldSelectArea.apply {
        setTitle(context.getString(R.string.field_select_detection_area_title))
        setOnClickListener { debounceUserInteraction { showDetectionAreaSelector() } }
    }

    private fun configureThresholdField() = viewBinding.fieldSliderThreshold.apply {
        setTitle(context.getString(R.string.generic_condition_threshold_title))
        setValueLabelState(isEnabled = true, prefix = "%")
        setSliderRange(0f, MAX_THRESHOLD)
        setOnValueChangedFromUserListener { value -> viewModel.setThreshold(value.roundToInt()) }
    }

    override fun onDialogCreated(dialog: BottomSheetDialog) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch { viewModel.isEditingCondition.collect(::onConditionEditingStateChanged) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.name.collect(::updateConditionName) }
                launch { viewModel.nameError.collect(viewBinding.fieldEditName::setError) }
                launch { viewModel.imageReferences.collect(::updateImageReferences) }
                launch { viewModel.imageImportResults.collect(::onImageImportResult) }
                launch { viewModel.referenceSaveFailures.collect(::onReferenceSaveFailure) }
                launch { viewModel.shouldBeDetected.collect(::updateShouldBeDetected) }
                launch { viewModel.detectionType.collect(::updateDetectionType) }
                launch { viewModel.threshold.collect(::updateThreshold) }
                launch { viewModel.conditionCanBeSaved.collect(::updateSaveButton) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.monitorSaveButtonView(viewBinding.layoutTopBar.buttonSave)
        viewModel.monitorDetectionTypeItemInAreaView(viewBinding.fieldDetectionType.multiStateButton.buttonRight)
        viewModel.monitorDetectionAreaSelector(viewBinding.fieldSelectArea.root)
        viewModel.monitorVisibilityView(viewBinding.fieldShouldAppear.root)
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopViewMonitoring()
    }

    override fun back() {
        if (viewModel.hasUnsavedModifications()) {
            context.showCloseWithoutSavingDialog {
                listener.onDismissClicked()
                super.back()
            }
            return
        }

        listener.onDismissClicked()
        super.back()
    }

    private fun onDeleteClicked() {
        if (viewModel.isConditionRelatedToClick()) {
            context.showDeleteConditionsWithAssociatedActionsDialog { confirmDelete() }
            return
        }

        confirmDelete()
    }

    private fun updateConditionName(newName: String?) {
        viewBinding.fieldEditName.setText(newName)
    }

    private fun updateImageReferences(state: ImageReferencesState) {
        imageReferenceAdapter.submitItems(state.items)
        viewBinding.buttonAddImageReference.apply {
            isEnabled = state.canAdd
            text = context.getString(
                R.string.image_reference_add_count,
                state.items.size,
                IMAGE_REFERENCES_LIMIT,
            )
        }
    }

    private fun updateShouldBeDetected(newValue: Boolean) {
        viewBinding.fieldShouldAppear.apply {
            setChecked(newValue)
            setDescription(if (newValue) 1 else 0)
        }
    }

    private fun updateDetectionType(detectionTypeState: DetectionTypeState) {
        currentDetectionType = detectionTypeState.type
        val index = when (detectionTypeState.type) {
            EXACT -> 0
            WHOLE_SCREEN -> 1
            IN_AREA -> 2
            else -> return
        }

        viewBinding.fieldDetectionType.apply {
            setChecked(index)
            setDescription(index)
        }

        viewBinding.fieldSelectArea.apply {
            setEnabled(detectionTypeState.type == IN_AREA)
            setDescription(detectionTypeState.areaText)
        }
    }

    private fun updateThreshold(newThreshold: Int) {
        viewBinding.fieldSliderThreshold.setSliderValue(newThreshold.toFloat())
    }

    private fun updateSaveButton(isValidCondition: Boolean) {
        viewBinding.layoutTopBar.setButtonEnabledState(DialogNavigationButton.SAVE, isValidCondition)
    }

    private fun showDetectionAreaSelector() {
        overlayManager.navigateTo(
            context = context,
            newOverlay = ConditionAreaSelectorMenu(
                onAreaSelected = viewModel::setDetectionArea,
            ),
            hideCurrent = true,
        )
    }

    private fun createImageReferenceAdapter() = ImageReferenceAdapter(
        onReplace = { index -> showImageSourceSelection(index) },
        onRemove = viewModel::removeReference,
        onOrderChanged = viewModel::reorderReferences,
        onDragRequested = referenceTouchHelper::startDrag,
    )

    private fun showImageSourceSelection(replacementIndex: Int?) {
        overlayManager.navigateTo(
            context = context,
            newOverlay = ImageSourceSelectionDialog { choice ->
                when (choice) {
                    ImageSourceChoice.CaptureScreen -> showImageCapture(replacementIndex)
                    ImageSourceChoice.ChooseFile -> viewModel.requestImageImport(context, replacementIndex)
                }
            },
        )
    }

    private fun showImageCapture(replacementIndex: Int?) {
        overlayManager.navigateTo(
            context = context,
            newOverlay = CaptureMenu { area, bitmap ->
                saveCapturedReference(area, bitmap, replacementIndex)
            },
            hideCurrent = true,
        )
    }

    private fun saveCapturedReference(area: Rect, bitmap: Bitmap, replacementIndex: Int?) {
        val referenceArea = if (currentDetectionType == EXACT) area else viewModel.centerImageArea(bitmap)
        viewModel.saveReference(referenceArea, bitmap, replacementIndex)
    }

    private fun onImageImportResult(pendingImport: PendingImageImport) {
        when (val result = pendingImport.result) {
            is ImageImportResult.Success -> showImportedImageUsage(result.bitmap, pendingImport.replacementIndex)
            is ImageImportResult.Failure -> context.showImageImportFailure(result.reason) {
                viewModel.requestImageImport(context, pendingImport.replacementIndex)
            }
            ImageImportResult.Cancelled -> Unit
        }
    }

    private fun onReferenceSaveFailure(pendingSave: PendingReferenceSave) {
        context.showImagePersistenceFailure {
            viewModel.saveReference(pendingSave.area, pendingSave.bitmap, pendingSave.replacementIndex)
        }
    }

    private fun showImportedImageUsage(bitmap: Bitmap, replacementIndex: Int?) {
        overlayManager.navigateTo(
            context = context,
            newOverlay = ImportedImageUsageDialog { choice ->
                when (choice) {
                    ImportedImageUsageChoice.WholeImage -> positionImportedImage(bitmap, replacementIndex)
                    ImportedImageUsageChoice.CropImage -> cropImportedImage(bitmap, replacementIndex)
                }
            },
        )
    }

    private fun cropImportedImage(bitmap: Bitmap, replacementIndex: Int?) {
        overlayManager.navigateTo(
            context = context,
            newOverlay = CaptureMenu(importedBitmap = bitmap) { _, croppedBitmap ->
                positionImportedImage(croppedBitmap, replacementIndex)
            },
            hideCurrent = true,
        )
    }

    private fun positionImportedImage(bitmap: Bitmap, replacementIndex: Int?) {
        val imageArea = viewModel.centerImageArea(bitmap)
        if (currentDetectionType != EXACT) {
            viewModel.saveReference(imageArea, bitmap, replacementIndex)
            return
        }

        showReferencePositionSelector(imageArea, bitmap, replacementIndex)
    }

    private fun showReferencePositionSelector(area: Rect, bitmap: Bitmap, replacementIndex: Int?) {
        overlayManager.navigateTo(
            context = context,
            newOverlay = ReferencePositionSelectorMenu(area) { selectedArea ->
                viewModel.saveReference(
                    viewModel.fixImagePosition(selectedArea, area),
                    bitmap,
                    replacementIndex,
                )
            },
            hideCurrent = true,
        )
    }

    private fun confirmDelete() {
        listener.onDeleteClicked()
        super.back()
    }

    private fun onConditionEditingStateChanged(isEditingCondition: Boolean) {
        if (!isEditingCondition) {
            Log.e(TAG, "Closing ConditionDialog because there is no condition edited")
            finish()
        }
    }

    private fun Int?.fromIndexToDetectionType() : Int =
        when (this) {
            0 -> EXACT
            1 -> WHOLE_SCREEN
            else -> IN_AREA
        }
}

private const val TAG = "ConditionDialog"
