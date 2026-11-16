package com.buzbuz.smartautoclicker.feature.script.config

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import android.text.InputType
import android.view.View
import com.buzbuz.smartautoclicker.core.script.packages.configurationItems
import com.google.android.material.checkbox.MaterialCheckBox
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
        val suppliedDraft = Json.parseToJsonElement(savedInstanceState?.getString("draft")
            ?: requireArguments().getString("initial") ?: "{}").jsonObject
        val restored = manifest.normalizeDraft(suppliedDraft)
        val configuration = requireArguments().getString("configuration")?.let { Json.parseToJsonElement(it).jsonObject }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val switches = mutableMapOf<String, MaterialSwitch>()
        val explanations = mutableMapOf<String, TextView>()
        val readers = mutableMapOf<String, () -> JsonElement>()
        var onAnswersChanged: () -> Unit = {}
        val parameterViews = mutableMapOf<String, List<View>>()
        manifest.parameters.forEach { (name, parameter) ->
            val firstChild = column.childCount
            when (parameter.type) {
                "selection" -> {
                    column.addView(TextView(context).apply { text = parameter.label })
                    val items = configurationItems(configuration)
                    val available = items.map { it.getValue("id").jsonPrimitive.content }.toSet()
                    val previous = restored.getValue(name).jsonArray.map { it.jsonPrimitive.content }.filter { it in available }
                    val selected = (if (name !in suppliedDraft) available.toList() else previous).toMutableSet()
                    val inputs = items.map { item ->
                        val id = item.getValue("id").jsonPrimitive.content
                        MaterialCheckBox(context).apply {
                            text = "${item.getValue("label").jsonPrimitive.content} ($id)"
                            isChecked = id in selected
                            minHeight = (48 * resources.displayMetrics.density).toInt()
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) selected.add(id) else selected.remove(id)
                                onAnswersChanged()
                            }
                            column.addView(this)
                        }
                    }
                    readers[name] = { JsonArray(items.mapIndexedNotNull { index, item ->
                        if (inputs[index].isChecked) item.getValue("id") else null
                    }) }
                    rawReaders[name] = readers.getValue(name)
                }
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
                    switches[name] = input
                    column.addView(input)
                    val explanation = TextView(context).apply { visibility = View.GONE }
                    explanations[name] = explanation
                    column.addView(explanation)
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
            parameterViews[name] = (firstChild until column.childCount).map { column.getChildAt(it) }
        }
        var applyingRules = false
        fun refreshRules() {
            if (applyingRules) return
            applyingRules = true
            try {
                val draft = manifest.normalizeDraft(JsonObject(rawReaders.mapValues { it.value() }))
                parameterViews.forEach { (name, views) ->
                    val conditions = manifest.parameters.getValue(name).visibleWhen
                    val visible = conditions.all { (source, expected) -> draft[source] == expected }
                    views.forEach { it.visibility = if (visible) View.VISIBLE else View.GONE }
                }
                switches.forEach { (name, input) -> input.isChecked = draft.getValue(name).jsonPrimitive.boolean }
                switches.forEach { (name, input) ->
                    val active = manifest.parameterRules.filter { it.forceFalse == name && it.applies(draft) }
                    input.isEnabled = active.isEmpty()
                    explanations.getValue(name).apply {
                        text = active.joinToString("\n") { it.message }
                        visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            } finally { applyingRules = false }
        }
        onAnswersChanged = ::refreshRules
        switches.values.forEach { it.setOnCheckedChangeListener { _, _ -> refreshRules() } }
        refreshRules()
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
                    manifest.validateSelections(validated, configuration)
                    parentFragmentManager.setFragmentResult(RESULT, Bundle().apply {
                        putString("answers", validated.toString())
                        putString("revision", requireArguments().getString("revision"))
                        putBoolean("newRound", requireArguments().getBoolean("newRound"))
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
        fun show(activity: FragmentActivity, manifest: ScriptManifest, revision: String, initial: JsonObject = JsonObject(emptyMap()), newRound: Boolean = false, configuration: JsonObject? = null) {
            if (manifest.parameters.isEmpty()) {
                activity.supportFragmentManager.setFragmentResult(RESULT, Bundle().apply { putString("answers", "{}"); putString("revision", revision); putBoolean("newRound", newRound) })
                return
            }
            if (activity.supportFragmentManager.findFragmentByTag(RESULT) != null) return
            ScriptStartParametersDialog().apply {
                arguments = Bundle().apply {
                    putString("manifest", manifest.document.toString())
                    putString("revision", revision)
                    putString("initial", initial.toString())
                    configuration?.let { putString("configuration", it.toString()) }
                    putBoolean("newRound", newRound)
                }
            }.show(activity.supportFragmentManager, RESULT)
        }
    }
}
