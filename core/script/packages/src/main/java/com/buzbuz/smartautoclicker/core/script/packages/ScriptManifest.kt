package com.buzbuz.smartautoclicker.core.script.packages

import kotlinx.serialization.json.*
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenProfile

data class ScriptManifest(
    val id: String,
    val name: String,
    val scriptVersion: String,
    val entry: String,
    val parameters: Map<String, ScriptParameter>,
    val resources: JsonObject,
    val document: JsonObject,
    val parameterRules: List<ScriptParameterRule> = emptyList(),
    val setup: ScriptSetup? = null,
    val targetPackage: String? = null,
) {
    /** Resolves start answers without changing package defaults. Example: `manifest.answers(userInput)`. */
    fun answers(supplied: JsonObject): JsonObject {
        require(supplied.keys.all { it in parameters }) { "answers=${supplied.keys}; expected declared parameter names ${parameters.keys}" }
        val resolved = JsonObject(parameters.mapValues { (name, parameter) ->
            val value = supplied[name] ?: parameter.default
            parameter.validate(name, value)
            value
        })
        parameterRules.forEach { it.validate(resolved) }
        return resolved
    }

    /** Clears excluded booleans for a form draft; final answers still require validation.
     * Example: `manifest.normalizeDraft(restoredAnswers)` before rendering switches.
     */
    fun normalizeDraft(draft: JsonObject): JsonObject {
        val answers = parameters.mapValues { (name, parameter) -> draft[name] ?: parameter.default }.toMutableMap()
        // Only true -> false changes are allowed, so dependent rules converge in at most N passes.
        repeat(parameters.size + 1) {
            var changed = false
            parameterRules.forEach { rule ->
                val target = rule.forceFalse
                if (target != null && rule.applies(JsonObject(answers)) && answers[target] != JsonPrimitive(false)) {
                    answers[target] = JsonPrimitive(false)
                    changed = true
                }
            }
            if (!changed) return JsonObject(answers)
        }
        error("parameterRules: normalization did not converge")
    }

    /** Rejects unavailable or empty selections before execution; e.g. `manifest.validateSelections(answers, catalog)`. */
    fun validateSelections(answers: JsonObject, configuration: JsonObject?) {
        if (parameters.values.none { it.type == "selection" }) return
        val available = configurationItems(configuration).map { it.text("id") }.toSet()
        parameters.filterValues { it.type == "selection" }.forEach { (name, _) ->
            val selected = answers.getValue(name).jsonArray
            require(selected.isNotEmpty() && selected.all { it.jsonPrimitive.content in available }) {
                "parameters.$name=$selected; selecione pelo menos um item da configuração atual."
            }
        }
    }

    companion object {
        /** Parses format/API v1 without running code. Example: `ScriptManifest.parse(manifestText)`. */
        fun parse(source: String): ScriptManifest {
            val document = Json.parseToJsonElement(source) as? JsonObject
                ?: throw IllegalArgumentException("manifest: expected a JSON object")
            require(document.integer("formatVersion") == 1) { "formatVersion=${document["formatVersion"]}; expected 1" }
            require(document.integer("apiVersion") == 1) { "apiVersion=${document["apiVersion"]}; expected 1" }
            val id = document.text("id")
            require(id.matches(Regex("[a-z0-9][a-z0-9.-]{0,79}"))) { "id=$id; expected 1..80 lowercase letters, digits, dots or hyphens" }
            val entry = document.text("entry")
            validatePackagePath(entry)
            require(entry.endsWith(".js")) { "entry=$entry; expected a .js source file" }
            val parameters = document["parameters"]?.let {
                val definitions = it as? JsonObject ?: throw IllegalArgumentException("parameters: expected an object")
                require(definitions.size <= 64) { "parameters.size=${definitions.size}; expected at most 64" }
                definitions.mapValues { (name, definition) ->
                    require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}"))) { "parameter.name=$name; expected an identifier of 1..64 characters" }
                    ScriptParameter.parse(name, definition as? JsonObject
                        ?: throw IllegalArgumentException("parameters.$name: expected an object"))
                }
            } ?: emptyMap()
            parameters.forEach { (name, parameter) ->
                parameter.visibleWhen.forEach { (source, expected) ->
                    val dependency = parameters[source]
                    require(dependency?.type == "boolean" && expected is JsonPrimitive && expected.booleanOrNull != null && !expected.isString) {
                        "parameters.$name.visibleWhen.$source=$expected; expected a declared boolean condition"
                    }
                }
            }
            val resources = document["resources"]?.let {
                it as? JsonObject ?: throw IllegalArgumentException("resources: expected an object")
            } ?: JsonObject(emptyMap())
            document["executionState"]?.let {
                require(it is JsonObject && it["version"] == JsonPrimitive(1)) { "executionState=$it; expected {version:1}" }
            }
            val setup = document["setup"]?.let(ScriptSetup::parse)
            require(setup == null || document.containsKey("executionState")) { "setup: expected executionState to enable private configuration" }
            require(parameters.values.none { it.type == "selection" } || setup != null) { "selection: expected a setup entry" }
            document["screen"]?.let(ScreenProfile::parse)
            val targetPackage = document["targetPackage"]?.let { value ->
                val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(name != null && name.length <= 255 && name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                    "targetPackage=$value; expected an Android package name, e.g. com.example.app"
                }
                name
            }
            val rules = document["parameterRules"]?.let { source ->
                val array = source as? JsonArray ?: error("parameterRules: expected an array")
                require(array.size <= 64) { "parameterRules.size=${array.size}; expected at most 64" }
                array.map { ScriptParameterRule.parse(it, parameters) }
            } ?: emptyList()
            return ScriptManifest(id, document.text("name"), document.text("scriptVersion"), entry, parameters, resources, document, rules, setup, targetPackage)
        }
    }
}

data class ScriptParameter(
    val type: String,
    val label: String,
    val default: JsonElement,
    val minimum: Double?,
    val maximum: Double?,
    val choices: List<String>,
    val visibleWhen: Map<String, JsonElement> = emptyMap(),
) {
    /** Validates one answer, e.g. `parameter.validate("limit", JsonPrimitive(20))`. */
    fun validate(name: String, value: JsonElement) {
        val primitive = value as? JsonPrimitive
        val valid = when (type) {
            "number" -> primitive?.takeUnless { it.isString }?.doubleOrNull?.let {
                it.isFinite() && (minimum == null || it >= minimum) && (maximum == null || it <= maximum)
            } == true
            "boolean" -> primitive?.takeUnless { it.isString }?.booleanOrNull != null
            "choice" -> primitive?.takeIf { it.isString }?.content in choices
            "selection" -> (value as? JsonArray)?.let { values ->
                values.size <= 64 && values.distinct().size == values.size && values.all {
                    it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 256
                }
            } == true
            else -> false
        }
        require(valid) { "parameters.$name=$value; expected $type (min=$minimum, max=$maximum, choices=$choices)" }
    }

    companion object {
        internal fun parse(name: String, definition: JsonObject): ScriptParameter {
            val type = definition.text("type")
            require(type in setOf("number", "boolean", "choice", "selection")) { "parameters.$name.type=$type; expected number, boolean, choice or selection" }
            fun bound(field: String): Double? = definition[field]?.let {
                val number = (it as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                require(number != null && number.isFinite()) { "parameters.$name.$field=$it; expected a finite number" }
                number
            }
            val minimum = bound("min")
            val maximum = bound("max")
            require(minimum == null || maximum == null || minimum <= maximum) {
                "parameters.$name.min=$minimum, max=$maximum; expected min <= max"
            }
            val choices = definition["choices"]?.let { value ->
                val array = value as? JsonArray ?: throw IllegalArgumentException("parameters.$name.choices: expected an array")
                array.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString && item.content.isNotBlank() }?.content
                    ?: throw IllegalArgumentException("parameters.$name.choices: expected nonempty strings") }
            } ?: emptyList()
            require(type != "choice" || (choices.isNotEmpty() && choices.distinct().size == choices.size)) {
                "parameters.$name.choices=$choices; expected nonempty distinct choices"
            }
            val default = definition["default"] ?: throw IllegalArgumentException("parameters.$name.default: expected a $type value")
            val visibleWhen = definition["visibleWhen"]?.let {
                it as? JsonObject ?: error("parameters.$name.visibleWhen=$it; expected an object")
            } ?: emptyMap()
            return ScriptParameter(type, definition.text("label"), default, minimum, maximum, choices, visibleWhen)
                .also { it.validate(name, default) }
        }
    }
}

internal fun JsonObject.text(field: String): String = (get(field) as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 256 }?.content
    ?: throw IllegalArgumentException("$field: expected a nonempty string of at most 256 characters")

internal fun JsonObject.integer(field: String): Int = (get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    ?: throw IllegalArgumentException("$field=${get(field)}; expected an integer")

internal fun validatePackagePath(path: String) {
    require(path.length in 1..200 && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
        "path=$path; expected a relative path without empty, dot or parent segments"
    }
}
