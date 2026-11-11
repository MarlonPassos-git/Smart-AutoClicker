package com.buzbuz.smartautoclicker.core.domain.model.counter

/** Author-defined numeric question shown before each run, e.g. a NUMBER bounded from 1 to 100. */
data class CounterStartQuestion(
    val title: String,
    val description: String = "",
    val order: Int = 0,
    val type: Type = Type.NUMBER,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val options: List<Option> = emptyList(),
    val visibleWhen: Visibility? = null,
) {
    enum class Type { BOOLEAN, CHOICE, NUMBER }
    data class Option(val label: String, val value: Double)
    data class Visibility(val counterName: String, val value: Double)

    /** Validates metadata and its default together; for example `question.isValid(50.0)`. */
    fun isValid(defaultValue: Double): Boolean =
        title.isNotBlank() && order >= 0 &&
            (visibleWhen == null || (visibleWhen.counterName.isNotBlank() && visibleWhen.value.isFinite())) &&
            (minimum == null || minimum.isFinite()) && (maximum == null || maximum.isFinite()) &&
            (minimum == null || maximum == null || minimum <= maximum) &&
            (type != Type.CHOICE || (options.isNotEmpty() &&
                options.all { it.label.isNotBlank() && it.value.isFinite() } &&
                options.map { it.value }.distinct().size == options.size)) &&
            accepts(defaultValue)

    /** Checks a proposed answer, e.g. `question.accepts(75.0)` before starting the engine. */
    fun accepts(value: Double): Boolean {
        if (!value.isFinite()) return false
        return when (type) {
            Type.BOOLEAN -> value == 0.0 || value == 1.0
            Type.CHOICE -> options.any { it.value == value }
            Type.NUMBER -> (minimum == null || value >= minimum) && (maximum == null || value <= maximum)
        }
    }

    /** Shows dependent fields for the selected answer; missing controllers keep the field editable.
     * Example: `question.isVisible(mapOf("mode" to 0.0))`.
     */
    fun isVisible(answers: Map<String, Double>): Boolean = visibleWhen?.let { condition ->
        answers[condition.counterName]?.let { it == condition.value } ?: true
    } ?: true
}

/** Validates the entire answer set before creating an execution snapshot; defaults remain untouched.
 * Example: `resolveCounterInitialValues(counters, mapOf("target" to 75.0))`.
 */
fun resolveCounterInitialValues(counters: List<Counter>, answers: Map<String, Double>): List<Counter> {
    val questions = counters.filter { it.startQuestion != null }.associateBy { it.counterName }
    require(answers.keys.all { it in questions }) { "answers: expected only declared question counters" }
    require(counters.all { it.isComplete() }) { "counters: expected valid definitions and defaults" }
    answers.forEach { (name, value) ->
        require(questions.getValue(name).startQuestion!!.accepts(value)) {
            "answer[$name]=$value: expected a value accepted by the question"
        }
    }
    return counters.map { counter -> counter.copy(defaultValue = answers[counter.counterName] ?: counter.defaultValue) }
}
