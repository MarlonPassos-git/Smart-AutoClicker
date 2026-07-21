/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.zoom

import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toPoint
import androidx.core.graphics.toPointF
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.common.actions.GESTURE_DURATION_MAX_VALUE
import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.PositionSelectorMenu
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.DialogNavigationButton
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.setButtonEnabledState
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setDescription
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setError
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setLabel
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnClickListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnTextChangedListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setText
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setTitle
import com.buzbuz.smartautoclicker.core.ui.utils.MinMaxInputFilter
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers.ZoomDescription
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.DialogConfigActionZoomBinding
import com.buzbuz.smartautoclicker.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.config.ui.action.OnActionConfigCompleteListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class ZoomDialog(
    private val listener: OnActionConfigCompleteListener,
) : OverlayDialog(R.style.ScenarioConfigTheme) {

    private val viewModel: ZoomViewModel by viewModels(
        entryPoint = ScenarioConfigViewModelsEntryPoint::class.java,
        creator = { zoomViewModel() },
    )
    private lateinit var viewBinding: DialogConfigActionZoomBinding

    override fun onCreateView(): ViewGroup {
        viewBinding = DialogConfigActionZoomBinding.inflate(LayoutInflater.from(context)).apply {
            setupTopBar()
            setupInputs()
            fieldGeometry.setTitle(context.getString(R.string.zoom_geometry))
            fieldGeometry.setOnClickListener { debounceUserInteraction(::showGeometrySelector) }
            buttonTest.setOnClickListener { viewModel.testZoom() }
        }
        return viewBinding.root
    }

    private fun DialogConfigActionZoomBinding.setupTopBar() {
        layoutTopBar.dialogTitle.setText(R.string.zoom_title)
        layoutTopBar.buttonDismiss.setDebouncedOnClickListener { back() }
        layoutTopBar.buttonSave.visibility = View.VISIBLE
        layoutTopBar.buttonSave.setDebouncedOnClickListener {
            viewModel.saveLastConfig()
            listener.onConfirmClicked()
            back()
        }
        layoutTopBar.buttonDelete.visibility = View.VISIBLE
        layoutTopBar.buttonDelete.setDebouncedOnClickListener {
            listener.onDeleteClicked()
            back()
        }
    }

    private fun DialogConfigActionZoomBinding.setupInputs() {
        fieldName.setLabel(R.string.generic_name)
        fieldName.setOnTextChangedListener { viewModel.setName(it.toString()) }
        fieldName.textField.filters = arrayOf(InputFilter.LengthFilter(context.resources.getInteger(R.integer.name_max_length)))
        fieldIntensity.setLabel(R.string.zoom_intensity)
        fieldIntensity.textField.filters = arrayOf(MinMaxInputFilter(1, 10_000))
        fieldIntensity.setOnTextChangedListener { viewModel.setIntensity(it.toString().toIntOrNull()) }
        fieldDuration.setLabel(R.string.zoom_duration)
        fieldDuration.textField.filters = arrayOf(MinMaxInputFilter(1, GESTURE_DURATION_MAX_VALUE.toInt()))
        fieldDuration.setOnTextChangedListener { viewModel.setDuration(it.toString().toLongOrNull()) }
        directionGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.setDirection(if (checkedId == R.id.direction_out) ZoomDirection.OUT else ZoomDirection.IN)
        }
    }

    override fun onDialogCreated(dialog: BottomSheetDialog) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect(::updateUi) }
        }
    }

    private fun updateUi(state: ZoomUiState?) {
        state ?: return
        viewBinding.apply {
            layoutTopBar.setButtonEnabledState(DialogNavigationButton.SAVE, state.canBeSaved)
            fieldName.setText(state.name)
            fieldName.setError(state.nameError)
            fieldIntensity.setText(state.intensity, InputType.TYPE_CLASS_NUMBER)
            fieldIntensity.setError(state.intensityError)
            fieldDuration.setText(state.duration, InputType.TYPE_CLASS_NUMBER)
            fieldDuration.setError(state.durationError)
            directionGroup.check(if (state.direction == ZoomDirection.IN) R.id.direction_in else R.id.direction_out)
            fieldGeometry.setDescription(state.geometryDescription ?: context.getString(R.string.generic_select_the_position))
            fieldGeometry.setError(state.geometryError)
            buttonTest.isEnabled = state.canBeSaved
        }
    }

    private fun showGeometrySelector() {
        val zoom = viewModel.getEditedZoom() ?: return
        overlayManager.navigateTo(
            context,
            PositionSelectorMenu(
                itemBriefDescription = ZoomDescription(
                    direction = zoom.direction,
                    center = zoom.center?.toPointF(),
                    intensityPx = zoom.intensityPx ?: 150,
                    zoomDurationMs = zoom.zoomDurationMs ?: 250,
                ),
                onConfirm = { description ->
                    val selected = description as? ZoomDescription ?: return@PositionSelectorMenu
                    val center = selected.center ?: return@PositionSelectorMenu
                    viewModel.setGeometry(center.toPoint(), selected.intensityPx)
                },
            ),
            hideCurrent = true,
        )
    }
}
