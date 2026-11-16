package com.buzbuz.smartautoclicker.core.script.packages

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ParameterRulesTest {
    private fun manifest(rules: String = """[
        {"when":{"multiple":true},"forceFalse":"walls","message":"Muros indisponíveis em Multivilas."},
        {"when":{"multiple":true},"requireAny":[{"parameter":"limit","greaterThan":0},{"parameter":"stop","notEquals":"nunca"}],"message":"Escolha um limite ou parada por recursos."}
    ]""") = ScriptManifest.parse("""{
        "formatVersion":1,"apiVersion":1,"id":"rules","name":"Regras","scriptVersion":"1","entry":"entry.js",
        "parameters":{
            "multiple":{"type":"boolean","label":"Multivilas","default":false},
            "walls":{"type":"boolean","label":"Muros","default":true},
            "limit":{"type":"number","label":"Limite","default":0,"min":0},
            "stop":{"type":"choice","label":"Parada","default":"nunca","choices":["nunca","cheio"]}
        },"parameterRules":$rules
    }""")

    @Test fun legacyAndInactiveRulesKeepDefaults() {
        assertTrue(manifest("[]").answers(JsonObject(emptyMap())).getValue("walls").jsonPrimitive.boolean)
        assertTrue(manifest().answers(JsonObject(emptyMap())).getValue("walls").jsonPrimitive.boolean)
    }

    @Test fun enablingExclusionClearsWallsAndDisablingDoesNotRestoreThem() {
        val manifest = manifest()
        val enabled = manifest.normalizeDraft(buildJsonObject { put("multiple", true); put("walls", true) })
        assertFalse(enabled.getValue("walls").jsonPrimitive.boolean)
        val disabled = manifest.normalizeDraft(JsonObject(enabled + ("multiple" to JsonPrimitive(false))))
        assertFalse(disabled.getValue("walls").jsonPrimitive.boolean)
    }

    @Test fun directAndRestoredAnswersCannotBypassExclusionOrTermination() {
        val manifest = manifest()
        val enabled = buildJsonObject { put("multiple", true); put("limit", 10) }
        assertThrows(IllegalArgumentException::class.java) { manifest.answers(enabled) }
        val normalized = manifest.normalizeDraft(enabled)
        assertEquals(10, manifest.answers(normalized).getValue("limit").jsonPrimitive.int)
        assertThrows(IllegalArgumentException::class.java) {
            manifest.answers(buildJsonObject { put("multiple", true); put("walls", false) })
        }
        assertEquals("cheio", manifest.answers(buildJsonObject {
            put("multiple", true); put("walls", false); put("stop", "cheio")
        }).getValue("stop").jsonPrimitive.content)
    }

    @Test fun malformedRulesAreRejectedDuringImport() {
        for (rules in listOf(
            """[{"when":{"missing":true},"forceFalse":"walls","message":"Erro"}]""",
            """[{"when":{"limit":0},"forceFalse":"walls","message":"Erro"}]""",
            """[{"when":{"multiple":true},"forceFalse":"limit","message":"Erro"}]""",
            """[{"when":{"multiple":true},"requireAny":[],"message":"Erro"}]""",
            """[{"when":{"multiple":true},"requireAny":[{"parameter":"walls","greaterThan":true}],"message":"Erro"}]""",
        )) assertThrows(IllegalArgumentException::class.java) { manifest(rules) }
    }
}
