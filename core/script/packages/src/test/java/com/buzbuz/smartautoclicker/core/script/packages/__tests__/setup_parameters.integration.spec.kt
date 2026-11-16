package com.buzbuz.smartautoclicker.core.script.packages

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SetupParametersIntegrationTest {
    private fun manifest() = ScriptManifest.parse("""{
        "formatVersion":1,"apiVersion":1,"id":"farm","name":"Farm","scriptVersion":"3","entry":"entry.js",
        "executionState":{"version":1},"setup":{"entry":"setup.js","label":"Configurar vilas"},
        "parameters":{
            "villages":{"type":"selection","label":"Vilas","default":[]},
            "walls":{"type":"boolean","label":"Muros","default":true},
            "reserve":{"type":"number","label":"Reserva","default":0,"visibleWhen":{"walls":true}}
        },"parameterRules":[{"when":{"villages":{"minItems":2}},"forceFalse":"walls","message":"Muros exigem uma vila."}]
    }""")

    @Test fun multipleSelectedTagsDisableWallsAndMissingOrStaleSelectionsCannotStart() {
        val manifest = manifest()
        val catalog = Json.parseToJsonElement("""{"formatVersion":1,"items":[{"id":"#TAG001","label":"Marlon ¹"},{"id":"#TAG002","label":"Marlon ²"}]}""").jsonObject
        val selected = buildJsonObject { put("villages", JsonArray(listOf(JsonPrimitive("#TAG001"), JsonPrimitive("#TAG002")))) }
        assertThrows(IllegalArgumentException::class.java) { manifest.answers(selected) }
        val normalized = manifest.normalizeDraft(selected)
        assertFalse(normalized.getValue("walls").jsonPrimitive.boolean)
        manifest.validateSelections(manifest.answers(normalized), catalog)
        val single = manifest.normalizeDraft(JsonObject(normalized + ("villages" to JsonArray(listOf(JsonPrimitive("#TAG001"))))))
        assertFalse(single.getValue("walls").jsonPrimitive.boolean)
        for (invalid in listOf(JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive("#REMOVED"))))) {
            assertThrows(IllegalArgumentException::class.java) {
                manifest.validateSelections(manifest.answers(buildJsonObject { put("villages", invalid) }), catalog)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { manifest.validateSelections(normalized, null) }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.answers(buildJsonObject { put("villages", JsonArray(listOf(JsonPrimitive("#TAG001"), JsonPrimitive("#TAG001")))) })
        }
    }

    @Test fun malformedSetupAndDuplicateCatalogItemsRequireRecovery() {
        val source = manifest().document.toString()
        assertThrows(IllegalArgumentException::class.java) { ScriptManifest.parse(source.replace("setup.js", "../setup.js")) }
        assertThrows(IllegalArgumentException::class.java) { ScriptManifest.parse(source.replace("\"minItems\":2", "\"minItems\":0")) }
        assertThrows(IllegalArgumentException::class.java) {
            configurationItems(Json.parseToJsonElement("""{"formatVersion":1,"items":[{"id":"same","label":"Uma"},{"id":"same","label":"Outra"}]}""").jsonObject)
        }
    }
}
