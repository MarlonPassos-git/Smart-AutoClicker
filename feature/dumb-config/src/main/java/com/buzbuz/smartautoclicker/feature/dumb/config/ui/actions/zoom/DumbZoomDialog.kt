/* Copyright (C) 2026 Kevin Buzeau */
package com.buzbuz.smartautoclicker.feature.dumb.config.ui.actions.zoom

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
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbAction
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.DialogNavigationButton
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.setButtonEnabledState
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setDescription
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setLabel
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnClickListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setOnTextChangedListener
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setText
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.setTitle
import com.buzbuz.smartautoclicker.core.ui.utils.MinMaxInputFilter
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers.ZoomDescription
import com.buzbuz.smartautoclicker.feature.dumb.config.R
import com.buzbuz.smartautoclicker.feature.dumb.config.databinding.DialogConfigDumbActionZoomBinding
import com.buzbuz.smartautoclicker.feature.dumb.config.di.DumbConfigViewModelsEntryPoint
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class DumbZoomDialog(
    private val dumbZoom: DumbAction.DumbZoom,
    private val onConfirmClicked: (DumbAction.DumbZoom) -> Unit,
    private val onDeleteClicked: (DumbAction.DumbZoom) -> Unit,
    private val onDismissClicked: () -> Unit,
) : OverlayDialog(R.style.AppTheme) {

    private val viewModel: DumbZoomViewModel by viewModels(
        entryPoint = DumbConfigViewModelsEntryPoint::class.java,
        creator = { dumbZoomViewModel() },
    )
    private lateinit var binding: DialogConfigDumbActionZoomBinding

    override fun onCreateView(): ViewGroup {
        viewModel.setEditedZoom(dumbZoom)
        binding = DialogConfigDumbActionZoomBinding.inflate(LayoutInflater.from(context)).apply {
            setupTopBar()
            fieldName.setLabel(R.string.input_field_label_name)
            fieldName.setOnTextChangedListener { viewModel.setName(it.toString()) }
            fieldIntensity.setLabel(R.string.zoom_intensity)
            fieldIntensity.textField.filters = arrayOf(MinMaxInputFilter(1, 10_000))
            fieldIntensity.setOnTextChangedListener { viewModel.setIntensity(it.toString().toIntOrNull() ?: 0) }
            fieldDuration.setLabel(R.string.zoom_duration)
            fieldDuration.textField.filters = arrayOf(MinMaxInputFilter(1, GESTURE_DURATION_MAX_VALUE.toInt()))
            fieldDuration.setOnTextChangedListener { viewModel.setDuration(it.toString().toLongOrNull() ?: 0) }
            directionGroup.setOnCheckedChangeListener { _, id ->
                viewModel.setDirection(if (id == R.id.direction_out) ZoomDirection.OUT else ZoomDirection.IN)
            }
            fieldGeometry.setTitle(context.getString(R.string.zoom_geometry))
            fieldGeometry.setOnClickListener { debounceUserInteraction(::showGeometrySelector) }
            buttonTest.setOnClickListener { viewModel.testZoom() }
        }
        return binding.root
    }

    private fun DialogConfigDumbActionZoomBinding.setupTopBar() {
        layoutTopBar.dialogTitle.setText(R.string.zoom_title)
        layoutTopBar.buttonDismiss.setDebouncedOnClickListener { onDismissClicked(); back() }
        layoutTopBar.buttonSave.visibility = View.VISIBLE
        layoutTopBar.buttonSave.setDebouncedOnClickListener {
            viewModel.getEditedZoom()?.let { viewModel.saveLastConfig(); onConfirmClicked(it) }
            back()
        }
        layoutTopBar.buttonDelete.visibility = View.VISIBLE
        layoutTopBar.buttonDelete.setDebouncedOnClickListener {
            viewModel.getEditedZoom()?.let(onDeleteClicked)
            back()
        }
    }

    override fun onDialogCreated(dialog: BottomSheetDialog) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isValid.collect { binding.layoutTopBar.setButtonEnabledState(DialogNavigationButton.SAVE, it) } }
                launch { viewModel.zoom.collect(::updateUi) }
            }
        }
    }

    private fun updateUi(zoom: DumbAction.DumbZoom) {
        binding.apply {
            fieldName.setText(zoom.name)
            fieldIntensity.setText(zoom.intensityPx.toString(), InputType.TYPE_CLASS_NUMBER)
            fieldDuration.setText(zoom.zoomDurationMs.toString(), InputType.TYPE_CLASS_NUMBER)
            directionGroup.check(if (zoom.direction == ZoomDirection.IN) R.id.direction_in else R.id.direction_out)
            fieldGeometry.setDescription("(${zoom.center.x}, ${zoom.center.y}) · ${zoom.intensityPx}px")
            buttonTest.isEnabled = zoom.isValid()
        }
    }

    private fun showGeometrySelector() {
        val zoom = viewModel.getEditedZoom() ?: return
        overlayManager.navigateTo(
            context,
            PositionSelectorMenu(
                itemBriefDescription = ZoomDescription(
                    zoom.direction,
                    zoom.center.toPointF(),
                    zoom.intensityPx,
                    zoom.zoomDurationMs,
                ),
                onConfirm = { description ->
                    val selected = description as? ZoomDescription ?: return@PositionSelectorMenu
                    viewModel.setGeometry(selected.center?.toPoint() ?: return@PositionSelectorMenu, selected.intensityPx)
                },
            ),
            hideCurrent = true,
        )
    }
}
