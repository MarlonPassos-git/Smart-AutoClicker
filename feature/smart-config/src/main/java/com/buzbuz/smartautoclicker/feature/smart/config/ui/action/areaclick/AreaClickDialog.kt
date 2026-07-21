package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick

import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.buzbuz.smartautoclicker.core.common.actions.GESTURE_DURATION_MAX_VALUE
import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClick
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.core.ui.bindings.buttons.MultiStateButtonConfig
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.DialogNavigationButton
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.setButtonEnabledState
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.*
import com.buzbuz.smartautoclicker.core.ui.utils.MinMaxInputFilter
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.DialogConfigActionAreaClickBinding
import com.buzbuz.smartautoclicker.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.config.ui.action.OnActionConfigCompleteListener
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetDialog

class AreaClickDialog(
    private val listener: OnActionConfigCompleteListener,
) : OverlayDialog(R.style.ScenarioConfigTheme) {
    private val viewModel: AreaClickViewModel by viewModels(
        entryPoint = ScenarioConfigViewModelsEntryPoint::class.java,
        creator = { areaClickViewModel() },
    )
    private lateinit var binding: DialogConfigActionAreaClickBinding

    override fun onCreateView(): ViewGroup {
        binding = DialogConfigActionAreaClickBinding.inflate(LayoutInflater.from(context))
        configureTopBar()
        configureInputs()
        configureDistribution()
        binding.fieldArea.setTitle(context.getString(R.string.field_area_click_area))
        binding.fieldArea.setOnClickListener { showAreaEditor() }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect(::render) }
        }
    }

    override fun onDialogCreated(dialog: BottomSheetDialog) = Unit

    private fun configureTopBar() = binding.layoutTopBar.apply {
        dialogTitle.setText(R.string.dialog_title_area_click)
        buttonDismiss.setDebouncedOnClickListener { dismissEdition() }
        buttonSave.visibility = View.VISIBLE
        buttonSave.setDebouncedOnClickListener { saveEdition() }
        buttonDelete.visibility = View.VISIBLE
        buttonDelete.setDebouncedOnClickListener { listener.onDeleteClicked(); back() }
    }

    private fun configureInputs() {
        configureNumberField(binding.fieldClickCount, R.string.field_area_click_count, 1, 50) {
            viewModel.setClickCount(it.toIntOrNull())
        }
        configureNumberField(binding.fieldPressDuration, R.string.input_field_label_click_press_duration, 1, GESTURE_DURATION_MAX_VALUE.toInt()) {
            viewModel.setPressDuration(it.toLongOrNull())
        }
        configureNumberField(binding.fieldInterval, R.string.field_area_click_interval, 0, AreaClick.INTERVAL_RANGE.last.toInt()) {
            viewModel.setInterval(it.toLongOrNull())
        }
        binding.fieldName.setLabel(R.string.generic_name)
        binding.fieldName.setOnTextChangedListener { viewModel.setName(it.toString()) }
        binding.fieldName.textField.filters = arrayOf(InputFilter.LengthFilter(context.resources.getInteger(R.integer.name_max_length)))
    }

    private fun configureNumberField(
        field: com.buzbuz.smartautoclicker.core.ui.databinding.IncludeFieldTextInputBinding,
        label: Int, minimum: Int, maximum: Int, onChanged: (String) -> Unit,
    ) {
        field.setLabel(label)
        field.textField.filters = arrayOf(MinMaxInputFilter(minimum, maximum))
        field.setOnTextChangedListener { onChanged(it.toString()) }
    }

    private fun configureDistribution() = binding.fieldDistribution.apply {
        setTitle(context.getString(R.string.field_area_click_distribution))
        setupDescriptions(listOf(context.getString(R.string.field_area_click_random), context.getString(R.string.field_area_click_distributed)))
        setButtonConfig(MultiStateButtonConfig(listOf(R.drawable.ic_area_click, R.drawable.ic_area_click), true, true))
        setOnCheckedListener { index ->
            viewModel.setDistribution(if (index == 0) AreaClickDistribution.RANDOM else AreaClickDistribution.DISTRIBUTED)
        }
    }

    private fun render(state: AreaClickUiState?) {
        state ?: return
        binding.layoutTopBar.setButtonEnabledState(DialogNavigationButton.SAVE, state.canBeSaved)
        binding.fieldName.setText(state.name)
        binding.fieldClickCount.setText(state.clickCount, InputType.TYPE_CLASS_NUMBER)
        binding.fieldPressDuration.setText(state.pressDuration, InputType.TYPE_CLASS_NUMBER)
        binding.fieldInterval.setText(state.interval, InputType.TYPE_CLASS_NUMBER)
        val distributionIndex = if (state.distribution == AreaClickDistribution.RANDOM) 0 else 1
        binding.fieldDistribution.setChecked(distributionIndex)
        binding.fieldDistribution.setDescription(distributionIndex)
        val description = if (state.verticesCount == 0) context.getString(R.string.field_area_click_area_empty)
        else context.getString(R.string.field_area_click_area_vertices, state.verticesCount)
        binding.fieldArea.setDescription(description)
        binding.fieldArea.setError(state.verticesCount == 0)
    }

    private fun showAreaEditor() {
        val vertices = viewModel.getEditedAreaClick()?.vertices ?: emptyList()
        overlayManager.navigateTo(context, AreaClickEditorMenu(vertices, viewModel::setVertices), hideCurrent = true)
    }

    private fun saveEdition() {
        viewModel.saveLastConfig()
        listener.onConfirmClicked()
        back()
    }

    private fun dismissEdition() {
        listener.onDismissClicked()
        back()
    }
}
