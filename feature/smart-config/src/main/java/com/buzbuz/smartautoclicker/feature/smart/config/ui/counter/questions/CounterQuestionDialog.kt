package com.buzbuz.smartautoclicker.feature.smart.config.ui.counter.questions

import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterStartQuestion
import com.buzbuz.smartautoclicker.core.ui.databinding.IncludeFieldTextInputBinding
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/** Author editor attached to a counter; e.g. save the returned counter in the scenario edition. */
class CounterQuestionDialog(private val counter: Counter, private val onSaved: (Counter) -> Unit) : QuestionFormDialog() {
    override val titleRes = R.string.start_questions_edit
    override val confirmRes = R.string.start_questions_save
    private var enabled = counter.startQuestion != null
    private var type = counter.startQuestion?.type ?: CounterStartQuestion.Type.NUMBER
    private val drafts = mutableMapOf(
        "title" to (counter.startQuestion?.title ?: counter.counterName),
        "description" to counter.startQuestion?.description.orEmpty(),
        "order" to (counter.startQuestion?.order ?: 0).toString(),
        "default" to counter.defaultValue.toString(),
        "minimum" to (counter.startQuestion?.minimum?.toString() ?: ""),
        "maximum" to (counter.startQuestion?.maximum?.toString() ?: ""),
        "options" to counter.startQuestion?.options.orEmpty().joinToString("\n") { "${it.value} = ${it.label}" },
        "visibleCounter" to counter.startQuestion?.visibleWhen?.counterName.orEmpty(),
        "visibleValue" to (counter.startQuestion?.visibleWhen?.value?.toString() ?: ""),
    )
    private val inputs = mutableMapOf<String, IncludeFieldTextInputBinding>()

    override fun buildFields(fields: LinearLayout) {
        inputs.clear()
        fields.addView(MaterialSwitch(context).apply {
            setText(R.string.start_questions_enabled)
            isChecked = enabled
            setOnCheckedChangeListener { _, checked -> enabled = checked }
        })
        addField(fields, "title", R.string.start_questions_label)
        addField(fields, "description", R.string.start_questions_description, multiline = true)
        fields.addView(RadioGroup(context).apply {
            listOf(
                CounterStartQuestion.Type.NUMBER to R.string.start_questions_number,
                CounterStartQuestion.Type.BOOLEAN to R.string.start_questions_boolean,
                CounterStartQuestion.Type.CHOICE to R.string.start_questions_choice,
            ).forEach { (value, label) ->
                addView(MaterialRadioButton(context).apply {
                    id = View.generateViewId()
                    setText(label)
                    isChecked = value == type
                    setOnCheckedChangeListener { _, checked -> if (checked) { type = value; updateTypeFields() } }
                })
            }
        })
        addField(fields, "default", R.string.start_questions_default, numeric = true)
        addField(fields, "order", R.string.start_questions_order, numeric = true)
        addField(fields, "minimum", R.string.start_questions_minimum, numeric = true)
        addField(fields, "maximum", R.string.start_questions_maximum, numeric = true)
        addField(fields, "options", R.string.start_questions_options, multiline = true)
        addField(fields, "visibleCounter", R.string.start_questions_visible_counter)
        addField(fields, "visibleValue", R.string.start_questions_visible_value, numeric = true)
        updateTypeFields()
    }

    private fun addField(parent: LinearLayout, key: String, label: Int, numeric: Boolean = false, multiline: Boolean = false) {
        inputs[key] = field(parent, context.getString(label), drafts.getValue(key), numeric, multiline) { drafts[key] = it }
    }

    private fun updateTypeFields() {
        inputs["minimum"]?.root?.visibility = if (type == CounterStartQuestion.Type.NUMBER) View.VISIBLE else View.GONE
        inputs["maximum"]?.root?.visibility = if (type == CounterStartQuestion.Type.NUMBER) View.VISIBLE else View.GONE
        inputs["options"]?.root?.visibility = if (type == CounterStartQuestion.Type.CHOICE) View.VISIBLE else View.GONE
    }

    override fun confirm(): Boolean {
        inputs.values.forEach { it.root.error = null }
        val default = number("default") ?: return false
        if (!enabled) {
            onSaved(counter.copy(defaultValue = default, startQuestion = null))
            return true
        }
        if (drafts.getValue("title").isBlank()) return error("title", R.string.start_questions_required)
        val order = drafts.getValue("order").toIntOrNull()
        if (order == null || order < 0) return error("order", R.string.start_questions_invalid_order, drafts.getValue("order"))
        val minimum = if (type == CounterStartQuestion.Type.NUMBER && drafts.getValue("minimum").isNotBlank()) number("minimum") ?: return false else null
        val maximum = if (type == CounterStartQuestion.Type.NUMBER && drafts.getValue("maximum").isNotBlank()) number("maximum") ?: return false else null
        if (minimum != null && maximum != null && minimum > maximum) return error("maximum", R.string.start_questions_invalid_limits)
        val options = if (type == CounterStartQuestion.Type.CHOICE) parseOptions() ?: return false else emptyList()
        val visibleCounter = drafts.getValue("visibleCounter").trim()
        val visibleWhen = if (visibleCounter.isBlank() && drafts.getValue("visibleValue").isBlank()) null else {
            if (visibleCounter.isBlank() || visibleCounter == counter.counterName) {
                return error("visibleCounter", R.string.start_questions_invalid_visibility, visibleCounter)
            }
            CounterStartQuestion.Visibility(visibleCounter, number("visibleValue") ?: return false)
        }
        val question = CounterStartQuestion(drafts.getValue("title"), drafts.getValue("description"), order, type, minimum, maximum, options, visibleWhen)
        if (!question.isValid(default)) return error("default", R.string.start_questions_invalid_answer,
            drafts.getValue("default"), when (type) {
                CounterStartQuestion.Type.BOOLEAN -> "0 ou 1"
                CounterStartQuestion.Type.CHOICE -> options.joinToString { it.value.toString() }
                CounterStartQuestion.Type.NUMBER -> context.getString(R.string.start_questions_range, minimum?.toString() ?: "−∞", maximum?.toString() ?: "∞")
            })
        onSaved(counter.copy(defaultValue = default, startQuestion = question))
        return true
    }

    private fun number(key: String): Double? {
        val value = drafts.getValue(key).toDoubleOrNull()?.takeIf { it.isFinite() }
        if (value == null) error(key, R.string.start_questions_invalid_number, drafts.getValue(key))
        return value
    }

    private fun parseOptions(): List<CounterStartQuestion.Option>? {
        val options = drafts.getValue("options").lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("=", limit = 2)
            val value = parts.first().trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            if (parts.size != 2 || value == null || parts[1].isBlank()) return invalidOptions()
            CounterStartQuestion.Option(parts[1].trim(), value)
        }.toList()
        if (options.isEmpty() || options.map { it.value }.distinct().size != options.size) return invalidOptions()
        return options
    }

    private fun invalidOptions(): List<CounterStartQuestion.Option>? {
        error("options", R.string.start_questions_invalid_options)
        return null
    }

    private fun error(key: String, message: Int, vararg args: Any): Boolean {
        inputs.getValue(key).root.error = context.getString(message, *args)
        inputs.getValue(key).textField.requestFocus()
        return false
    }
}
