package com.buzbuz.smartautoclicker.feature.smart.config.ui.counter.questions

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterStartQuestion
import com.buzbuz.smartautoclicker.core.ui.databinding.IncludeInputFieldDropdownBinding
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu.StartQuestionsRequest
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView

/** One form per Play; e.g. supply onConfirmed to start with an atomic answer snapshot. */
class StartQuestionsDialog(
    private val request: StartQuestionsRequest,
    private val onConfirmed: (Map<String, Double>) -> Unit,
    private val onCancelled: () -> Unit,
) : QuestionFormDialog() {
    override val titleRes = R.string.start_questions_title
    override val confirmRes = R.string.start_questions_start
    private val drafts = request.initialAnswers.mapValues { it.value.toInputText() }.toMutableMap()
    private val questionRows = mutableMapOf<Counter, LinearLayout>()
    private val validators = mutableMapOf<Counter, () -> Boolean>()
    private var confirmed = false

    override fun buildFields(fields: LinearLayout) {
        validators.clear()
        questionRows.clear()
        request.counters.filter { it.startQuestion != null }
            .sortedWith(compareBy({ it.startQuestion!!.order }, { it.counterName })).forEach { counter ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                questionRows[counter] = row
                fields.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = context.resources.getDimensionPixelSize(R.dimen.margin_vertical_default)
                })
                when (counter.startQuestion!!.type) {
                    CounterStartQuestion.Type.NUMBER -> numberField(row, counter)
                    CounterStartQuestion.Type.CHOICE -> choiceField(row, counter)
                    CounterStartQuestion.Type.BOOLEAN -> booleanField(row, counter)
                }
            }
        updateVisibility()
    }

    private fun numberField(parent: LinearLayout, counter: Counter) {
        val question = counter.startQuestion!!
        val input = field(parent, question.title, drafts[counter.counterName].orEmpty(), numeric = true) {
            drafts[counter.counterName] = it
            updateVisibility()
        }
        input.root.helperText = question.description.takeIf { it.isNotBlank() }
        validators[counter] = {
            val raw = drafts[counter.counterName].orEmpty()
            val valid = raw.toDoubleOrNull()?.let(question::accepts) == true
            input.root.error = if (valid) null else context.getString(
                R.string.start_questions_invalid_answer, raw,
                context.getString(R.string.start_questions_range,
                    question.minimum?.toInputText() ?: "−∞", question.maximum?.toInputText() ?: "∞"),
            )
            if (!valid) input.textField.requestFocus()
            valid
        }
    }

    private fun choiceField(parent: LinearLayout, counter: Counter) {
        val question = counter.startQuestion!!
        val input = IncludeInputFieldDropdownBinding.inflate(LayoutInflater.from(context), parent, false)
        input.textLayout.apply {
            hint = question.title
            helperText = question.description.takeIf { it.isNotBlank() }
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        input.textField.apply {
            setSimpleItems(question.options.map { it.label }.toTypedArray())
            setText(question.options.firstOrNull { it.value == drafts[counter.counterName]?.toDoubleOrNull() }?.label, false)
            setOnItemClickListener { _, _, position, _ ->
                val option = question.options[position]
                setText(option.label, false)
                drafts[counter.counterName] = option.value.toString()
                updateVisibility()
            }
        }
        parent.addView(input.root)
    }

    private fun booleanField(parent: LinearLayout, counter: Counter) {
        val question = counter.startQuestion!!
        parent.addView(MaterialSwitch(context).apply {
            text = question.title
            isChecked = drafts[counter.counterName]?.toDoubleOrNull() == 1.0
            setOnCheckedChangeListener { _, checked ->
                drafts[counter.counterName] = if (checked) "1.0" else "0.0"
                updateVisibility()
            }
        })
        if (question.description.isNotBlank()) {
            parent.addView(MaterialTextView(context).apply { text = question.description })
        }
    }

    private fun currentValues(): Map<String, Double> = request.counters.associate {
        it.counterName to (drafts[it.counterName]?.toDoubleOrNull() ?: it.defaultValue)
    }

    // Resource balances must remain editable with the numeric keyboard, which has no exponent key.
    private fun Double.toInputText(): String =
        if (isFinite()) toBigDecimal().stripTrailingZeros().toPlainString() else toString()

    private fun updateVisibility() {
        val answers = currentValues()
        questionRows.forEach { (counter, row) ->
            val visible = counter.startQuestion!!.isVisible(answers)
            if (!visible) row.clearFocus()
            row.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    override fun confirm(): Boolean {
        if (confirmed) return false
        val values = currentValues()
        if (validators.filterKeys { it.startQuestion!!.isVisible(values) }.values.map { it() }.any { !it }) return false
        // Hidden unfinished fields retain their draft for hide/show, but never block another mode.
        val answers = request.counters.filter { it.startQuestion != null }.associate { counter ->
            val value = values.getValue(counter.counterName)
            counter.counterName to (value.takeIf { counter.startQuestion!!.accepts(it) } ?: counter.defaultValue)
        }
        confirmed = true
        onConfirmed(answers)
        return true
    }

    override fun back() {
        if (!confirmed) onCancelled()
        super.back()
    }
}
