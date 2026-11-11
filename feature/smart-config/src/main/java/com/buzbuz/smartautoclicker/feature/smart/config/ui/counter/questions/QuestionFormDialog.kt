package com.buzbuz.smartautoclicker.feature.smart.config.ui.counter.questions

import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.ui.databinding.IncludeFieldTextInputBinding
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.DialogCounterQuestionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Shared scrolling Material overlay shell; e.g. subclasses add author or run fields in [buildFields]. */
abstract class QuestionFormDialog : OverlayDialog(R.style.ScenarioConfigTheme) {
    protected lateinit var binding: DialogCounterQuestionsBinding
    protected abstract val titleRes: Int
    protected abstract val confirmRes: Int
    protected abstract fun buildFields(fields: LinearLayout)
    protected abstract fun confirm(): Boolean

    final override fun onCreateView(): ViewGroup {
        binding = DialogCounterQuestionsBinding.inflate(LayoutInflater.from(context))
        binding.title.setText(titleRes)
        binding.cancel.setOnClickListener { back() }
        binding.confirm.apply {
            setText(confirmRes)
            setOnClickListener { if (confirm()) super.back() }
        }
        buildFields(binding.fields)
        return binding.root
    }

    final override fun onDialogCreated(dialog: BottomSheetDialog) = Unit

    protected fun field(parent: LinearLayout, label: String, value: String,
                        numeric: Boolean = false, multiline: Boolean = false,
                        onChanged: (String) -> Unit): IncludeFieldTextInputBinding {
        val field = IncludeFieldTextInputBinding.inflate(LayoutInflater.from(context), parent, false)
        field.root.hint = label
        field.textField.apply {
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) { setSingleLine(false); maxLines = 8; minLines = 2 }
            setText(value)
            doAfterTextChanged { onChanged(it.toString()); field.root.error = null }
        }
        parent.addView(field.root, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = context.resources.getDimensionPixelSize(R.dimen.margin_vertical_default)
        })
        return field
    }
}
