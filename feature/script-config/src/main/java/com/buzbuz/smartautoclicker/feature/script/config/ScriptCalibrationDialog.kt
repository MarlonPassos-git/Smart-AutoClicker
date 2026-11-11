package com.buzbuz.smartautoclicker.feature.script.config

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Names a stable image/region before requesting capture consent. */
class ScriptCalibrationDialog : DialogFragment() {
    private lateinit var name: TextInputEditText
    private lateinit var type: Spinner

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val field = TextInputLayout(context).apply { hint = getString(R.string.script_resource_name) }
        name = TextInputEditText(field.context).apply {
            isSingleLine = true
            setText(savedInstanceState?.getString("name") ?: "")
        }
        field.addView(name)
        column.addView(field)
        type = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.script_capture_image), getString(R.string.script_capture_region)))
            setSelection(savedInstanceState?.getInt("type") ?: 0)
            contentDescription = getString(R.string.script_resource_type)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
        }
        column.addView(type)
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.script_calibrate)
            .setView(column).setNegativeButton(R.string.script_cancel, null).setPositiveButton(R.string.script_continue, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = name.text.toString().trim()
                if (!value.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}"))) {
                    field.error = getString(R.string.script_resource_name_invalid, value)
                    return@setOnClickListener
                }
                parentFragmentManager.setFragmentResult(RESULT, Bundle().apply {
                    putString("revision", requireArguments().getString("revision"))
                    putString("name", value); putBoolean("image", type.selectedItemPosition == 0)
                })
                dismiss()
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("name", name.text.toString()); outState.putInt("type", type.selectedItemPosition)
        super.onSaveInstanceState(outState)
    }

    companion object {
        internal const val RESULT = "script-calibration"
        /** Opens a rotation-safe name/type form, e.g. before calibrating a saved revision. */
        fun show(activity: FragmentActivity, revision: String) {
            if (activity.supportFragmentManager.findFragmentByTag(RESULT) != null) return
            ScriptCalibrationDialog().apply { arguments = Bundle().apply { putString("revision", revision) } }
                .show(activity.supportFragmentManager, RESULT)
        }
    }
}
