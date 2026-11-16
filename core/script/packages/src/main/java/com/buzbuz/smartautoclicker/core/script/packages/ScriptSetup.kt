package com.buzbuz.smartautoclicker.core.script.packages

import kotlinx.serialization.json.*

/** Optional setup entry; its configuration outlives individual execution checkpoints. */
data class ScriptSetup(val entry: String, val label: String, val description: String? = null) {
    companion object {
        internal fun parse(value: JsonElement): ScriptSetup {
            val definition = value as? JsonObject ?: error("setup=$value; expected an object")
            val entry = definition.text("entry")
            validatePackagePath(entry)
            require(entry.endsWith(".js")) { "setup.entry=$entry; expected a .js source file" }
            return ScriptSetup(entry, definition.text("label"), definition["description"]?.let { definition.text("description") })
        }
    }
}

/** Validates the generic selectable part of private configuration; domain fields stay in the script.
 * Example: `configurationItems(repository.readConfiguration(id))` before displaying a selection.
 */
fun configurationItems(configuration: JsonObject?): List<JsonObject> {
    require(configuration?.get("formatVersion") == JsonPrimitive(1)) { "Configuração ausente ou incompatível; execute Configurar antes de iniciar." }
    val items = configuration["items"] as? JsonArray
    require(items != null && items.size in 1..64) { "configuration.items: esperado de 1 a 64 itens; execute Configurar novamente." }
    val ids = mutableSetOf<String>()
    return items.map { item ->
        val definition = item as? JsonObject ?: error("configuration.items: esperado um objeto por item")
        val id = definition.text("id")
        definition.text("label")
        require(ids.add(id)) { "configuration.items.id=$id; esperado identificador único" }
        definition
    }
}
