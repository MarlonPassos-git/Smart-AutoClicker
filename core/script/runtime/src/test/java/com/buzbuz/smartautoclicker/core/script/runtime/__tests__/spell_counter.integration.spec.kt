package com.buzbuz.smartautoclicker.core.script.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SpellCounterIntegrationTest {
    @Test(timeout = 15000)
    fun overlappingSeventyOneAndSevenReadingsRequireFocusedAgreement() = runBlocking {
        assertEquals(7, readConflictingCounter(listOf("x7", "x7", "x71")))
    }

    @Test(timeout = 15000)
    fun oneFocusedReadingDoesNotAuthorizePickingTheSmallerCounter() = runBlocking {
        assertEquals(null, readConflictingCounter(listOf("x7", "x?", "x71")))
    }

    @Test(timeout = 15000)
    fun focusedAgreementCannotIncreaseThePreviouslyKnownCounter() = runBlocking {
        assertEquals(null, readConflictingCounter(listOf("x71", "x71", "x7")))
    }

    @Test(timeout = 15000)
    fun aConfirmedZeroRemainsZeroWithoutAnExtraCast() = runBlocking {
        assertEquals(0, readConflictingCounter(listOf("x0", "x0", "x?")))
    }

    private suspend fun readConflictingCounter(focused: List<String>): Int? {
        val source = checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/spells.js"))
            .bufferedReader().use { it.readText() }
        val program = ScriptProgram("entry.js", mapOf("spells.js" to source, "entry.js" to """
            import {readSpellCards} from './spells.js';
            import {log} from 'klickr';
            const current = {frame:{frame:1,width:2340}, calibration:{regions:{troops:{left:160,top:920,right:2000,bottom:970}}}};
            try {
                const cards = await readSpellCards(current, 1755/2340, [{xRatio:1810/2340,quantity:9}]);
                if (cards.length !== 1) throw new Error('Expected one spell card');
                await log(String(cards[0].quantity));
            } catch (error) {
                if (error.code !== 'conflicting_spell_counter') throw error;
                await log('conflict');
            }
        """.trimIndent()))
        var focusedIndex = 0
        var reported: String? = null
        QuickJsScriptRuntime(Dispatchers.Default).execute(program) { request ->
            val call = Json.parseToJsonElement(request).jsonObject
            when (call.getValue("operation").jsonPrimitive.content) {
                "readText" -> {
                    val region = call.getValue("region").jsonObject
                    val left = region.getValue("left").jsonPrimitive.int
                    val right = region.getValue("right").jsonPrimitive.int
                    val reading = when {
                        left == 1755 && right == 1855 -> "x71" to 1805
                        left == 1775 && right == 1915 -> "x7" to 1820
                        right - left in listOf(100, 60, 110) && left >= 1755 -> focused[focusedIndex++] to 1810
                        else -> null
                    }
                    buildJsonObject {
                        put("truncated", false)
                        putJsonArray("blocks") {
                            reading?.let { (text, x) -> add(buildJsonObject {
                                put("text", text); put("left", x - 10); put("right", x + 10)
                                put("top", 920); put("bottom", 960)
                            }) }
                        }
                    }.toString()
                }
                "read" -> """{"status":"not_found"}"""
                "log" -> { reported = call.getValue("message").jsonPrimitive.content; "{}" }
                else -> error("Unexpected operation: $call")
            }
        }
        assertEquals(3, focusedIndex)
        return reported?.takeUnless { it == "conflict" }?.toInt()
    }
}
