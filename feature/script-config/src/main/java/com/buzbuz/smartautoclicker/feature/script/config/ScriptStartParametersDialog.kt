package com.buzbuz.smartautoclicker.feature.script.config

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.buzbuz.smartautoclicker.core.script.packages.ScriptManifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.serialization.json.*

/** The same manifest validation governs defaults, typed input and the final service request. */
class ScriptStartParametersDialog : DialogFragment() {
    private val rawReaders = mutableMapOf<String, () -> JsonElement>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val manifest = ScriptManifest.parse(requireArguments().getString("manifest")!!)
        val restored = Json.parseToJsonElement(savedInstanceState?.getString("draft")
            ?: requireArguments().getString("initial") ?: "{}").jsonObject
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val readers = mutableMapOf<String, () -> JsonElement>()
        manifest.parameters.forEach { (name, parameter) ->
            when (parameter.type) {
                "number" -> {
                    val layout = TextInputLayout(context).apply { hint = parameter.label }
                    val input = TextInputEditText(layout.context).apply {
                        id = View.generateViewId()
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                        setText((restored[name] ?: parameter.default).jsonPrimitive.content)
                    }
                    layout.addView(input)
                    column.addView(layout)
                    rawReaders[name] = { JsonPrimitive(input.text.toString()) }
                    readers[name] = {
                        val text = input.text.toString().trim().replace(',', '.')
                        val number = text.toDoubleOrNull()
                        require(number != null && number.isFinite()) { "${parameter.label}=$text; esperado um número finito" }
                        JsonPrimitive(number)
                    }
                }
                "boolean" -> {
                    val input = MaterialSwitch(context).apply {
                        text = parameter.label
                        isChecked = (restored[name] ?: parameter.default).jsonPrimitive.boolean
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                    }
                    column.addView(input)
                    readers[name] = { JsonPrimitive(input.isChecked) }
                    rawReaders[name] = readers.getValue(name)
                }
                "choice" -> {
                    val input = Spinner(context).apply {
                        id = View.generateViewId()
                        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, parameter.choices)
                        setSelection(parameter.choices.indexOf((restored[name] ?: parameter.default).jsonPrimitive.content).coerceAtLeast(0))
                        minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    }
                    column.addView(TextView(context).apply { text = parameter.label; labelFor = input.id })
                    column.addView(input)
                    readers[name] = { JsonPrimitive(parameter.choices[input.selectedItemPosition]) }
                    rawReaders[name] = readers.getValue(name)
                }
            }
        }
        val error = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(error)
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.script_start_parameters)
            .setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton(R.string.script_cancel, null).setPositiveButton(R.string.script_start, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val supplied = JsonObject(readers.mapValues { it.value() })
                    val validated = manifest.answers(supplied)
                    parentFragmentManager.setFragmentResult(RESULT, Bundle().apply {
                        putString("answers", validated.toString())
                        putString("revision", requireArguments().getString("revision"))
                    })
                    dialog.dismiss()
                } catch (failure: Exception) {
                    error.text = context.getString(R.string.script_invalid_parameters, failure.message)
                }
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", JsonObject(rawReaders.mapValues { it.value() }).toString())
        super.onSaveInstanceState(outState)
    }

    companion object {
        internal const val RESULT = "script-start-parameters"

        /** Opens a rotation-safe form for one reviewed revision, e.g. before requesting capture consent. */
        fun show(activity: FragmentActivity, manifest: ScriptManifest, revision: String, initial: JsonObject = JsonObject(emptyMap())) {
            if (manifest.parameters.isEmpty()) {
                activity.supportFragmentManager.setFragmentResult(RESULT, Bundle().apply { putString("answers", "{}"); putString("revision", revision) })
                return
            }
            if (activity.supportFragmentManager.findFragmentByTag(RESULT) != null) return
            ScriptStartParametersDialog().apply {
                arguments = Bundle().apply {
                    putString("manifest", manifest.document.toString())
                    putString("revision", revision)
                    putString("initial", initial.toString())
                }
            }.show(activity.supportFragmentManager, RESULT)
        }
    }
}
