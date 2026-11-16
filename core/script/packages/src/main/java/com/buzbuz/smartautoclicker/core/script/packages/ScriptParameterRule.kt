package com.buzbuz.smartautoclicker.core.script.packages

import kotlinx.serialization.json.*

/** Delimited conditional constraints for API v1; this is not a JSON Schema interpreter. */
data class ScriptParameterRule(
    val whenAnswers: Map<String, JsonElement>,
    val forceFalse: String?,
    val requireAny: List<ScriptAnswerCondition>,
    val message: String,
) {
    /** Matches resolved answers, e.g. `rule.applies(manifest.answers(input))`. */
    fun applies(answers: JsonObject): Boolean = whenAnswers.all { (name, value) ->
        if (value is JsonObject) {
            (answers[name] as? JsonArray)?.size?.let { it >= value.getValue("minItems").jsonPrimitive.int } == true
        } else answers[name] == value
    }

    internal fun validate(answers: JsonObject) {
        if (!applies(answers)) return
        require(forceFalse == null || answers[forceFalse] == JsonPrimitive(false)) {
            "$forceFalse=${answers[forceFalse]}; $message"
        }
        require(requireAny.isEmpty() || requireAny.any { it.matches(answers) }) { message }
    }

    companion object {
        internal fun parse(value: JsonElement, parameters: Map<String, ScriptParameter>): ScriptParameterRule {
            val rule = value as? JsonObject ?: throw IllegalArgumentException("parameterRules: expected rule objects")
            val whenAnswers = rule["when"] as? JsonObject
            require(!whenAnswers.isNullOrEmpty()) { "parameterRules.when: expected a nonempty answer object" }
            whenAnswers.forEach { (name, answer) ->
                val parameter = requireNotNull(parameters[name]) { "parameterRules.when.$name: expected a declared parameter" }
                if (parameter.type == "selection") {
                    val count = (answer as? JsonObject)?.get("minItems") as? JsonPrimitive
                    require(answer is JsonObject && answer.keys == setOf("minItems") && count?.isString == false && count.intOrNull in 1..64) {
                        "parameterRules.when.$name=$answer; expected {minItems:1..64}"
                    }
                } else {
                    require(parameter.type == "boolean") { "parameterRules.when.$name: expected a boolean or selection parameter" }
                    parameter.validate(name, answer)
                }
            }
            val forceFalse = rule["forceFalse"]?.let { rule.text("forceFalse") }
            require(forceFalse == null || parameters[forceFalse]?.type == "boolean") {
                "parameterRules.forceFalse=$forceFalse; expected a boolean parameter"
            }
            val conditions = rule["requireAny"]?.let { source ->
                val array = source as? JsonArray ?: throw IllegalArgumentException("parameterRules.requireAny: expected an array")
                require(array.size in 1..64) { "parameterRules.requireAny.size=${array.size}; expected 1..64 conditions" }
                array.map { ScriptAnswerCondition.parse(it, parameters) }
            } ?: emptyList()
            require(forceFalse != null || conditions.isNotEmpty()) { "parameterRules: expected forceFalse or requireAny" }
            return ScriptParameterRule(whenAnswers, forceFalse, conditions, rule.text("message"))
        }
    }
}

data class ScriptAnswerCondition(val parameter: String, val operator: String, val value: JsonElement) {
    internal fun matches(answers: JsonObject): Boolean = when (operator) {
        "notEquals" -> answers[parameter] != value
        "equals" -> answers[parameter] == value
        "greaterThan" -> answers.getValue(parameter).jsonPrimitive.double > value.jsonPrimitive.double
        else -> false
    }

    companion object {
        internal fun parse(value: JsonElement, parameters: Map<String, ScriptParameter>): ScriptAnswerCondition {
            val condition = value as? JsonObject ?: throw IllegalArgumentException("parameterRules.requireAny: expected condition objects")
            val name = condition.text("parameter")
            val parameter = requireNotNull(parameters[name]) { "parameterRules.parameter=$name; expected a declared parameter" }
            val operators = condition.keys.intersect(setOf("equals", "notEquals", "greaterThan"))
            require(operators.size == 1) { "parameterRules.$name: expected exactly one of equals, notEquals or greaterThan" }
            val operator = operators.single()
            val operand = condition.getValue(operator)
            parameter.validate(name, operand)
            require(operator != "greaterThan" || parameter.type == "number") { "parameterRules.$name.greaterThan: expected a number parameter" }
            return ScriptAnswerCondition(name, operator, operand)
        }
    }
}
