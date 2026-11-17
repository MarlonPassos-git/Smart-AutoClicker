package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MultivillageIntegrationTest {
    @Test(timeout = 20000) fun consecutiveAttacksReuseAccountConfirmationUntilTheNextVillage() = runBlocking {
        val result = execute("""
            let reads = 0;
            await runRound(options, {
                currentIdentity: async () => { reads++; return '#INITIAL'; },
                configuredVillages: async () => [{id:'#INITIAL',key:'initial',name:'Inicial',attacks:0,status:'pending'}],
                switchVillage: async () => { throw new Error('Unexpected switch'); },
                runVillage: async (_, callbacks) => {
                    await callbacks.onAttempt();
                    const first = reads;
                    await callbacks.onConfirmed(1);
                    await callbacks.onAttempt();
                    if (reads !== first) throw new Error('Settings reopened between attacks');
                    await callbacks.onConfirmed(2);
                    return 'Done';
                }
            });
        """)
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
    }

    @Test(timeout = 20000) fun finiteRoundAppliesTheSameLimitPerIdentityAndCheckpointsBeforeEffects() = runBlocking {
        val result = execute("""
            const calls = [];
            let account = '#INITIAL';
            await runRound(options, {
                currentIdentity: async () => account,
                configuredVillages: async () => [
                    { id: '#INITIAL', key: 'second', name: 'Inicial', capacities: {goldCapacity:31000000}, attacks: 0, status: 'pending' },
                    { id: '#OTHER01', key: 'first', name: 'Outra', capacities: {goldCapacity:25000000}, attacks: 0, status: 'pending' }],
                switchVillage: async village => { account = village.id; },
                villageCapacities: async () => ({ goldCapacity: account === '#INITIAL' ? 31000000 : 25000000 }),
                runVillage: async (parameters, callbacks) => {
                    if (parameters.improveWalls) throw new Error('Walls must stay disabled');
                    let count = callbacks.attacks;
                    while (count < parameters.maxBattles) {
                        await callbacks.onAttempt();
                        const pending = await loadState();
                        if (pending.pending.kind !== 'attack' || pending.pending.village !== account) throw new Error('Effect without checkpoint');
                        calls.push(account);
                        await callbacks.onConfirmed(++count);
                    }
                    return 'Limite por vila atingido';
                }
            });
        """.trimIndent())
        assertEquals(ScriptSessionState.COMPLETED, result.status.state)
        val saved = result.state!!
        assertEquals("completed", saved.getValue("stage").jsonPrimitive.content)
        val villages = saved.getValue("villages").jsonArray.map { it.jsonObject }
        assertEquals(listOf(2, 2), villages.map { it.getValue("attacks").jsonPrimitive.int })
        assertEquals(listOf("completed", "completed"), villages.map { it.getValue("status").jsonPrimitive.content })
        assertEquals(listOf(31000000, 25000000), villages.map { it.getValue("capacities").jsonObject.getValue("goldCapacity").jsonPrimitive.int })
        assertTrue(result.completed)
    }

    @Test(timeout = 20000) fun completingALargeRoundKeepsMessagesBoundedAndPreservesAllVillageDetails() = runBlocking {
        val result = execute("""
            let current = '#TAG0000';
            await runRound(options, {
                currentIdentity: async () => current,
                configuredVillages: async () => Array.from({length:64}, (_, index) => ({
                    id:'#TAG'+String(index).padStart(4,'0'), key:'account'+index,
                    name:'Vila '+index+' '+ 'x'.repeat(100), attacks:0, status:'pending' })),
                switchVillage: async village => { current = village.id; },
                villageCapacities: async () => ({}),
                runVillage: async () => 'Condição de recursos atingida'
            });
        """)
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
        assertTrue(result.completed)
        assertEquals(64, result.state!!.getValue("details").jsonArray.size)
        assertTrue(result.state.getValue("details").toString().length > 4096)
    }

    @Test(timeout = 20000) fun resourceCompletionAdvancesAndCompletedVillagesAreNeverReplayedAfterRestart() = runBlocking {
        val options = options()
        val previous = buildJsonObject {
            put("version", 1); put("options", options); put("stage", "ready")
            put("villages", Json.parseToJsonElement("""[
                {"id":"#DONE001","key":"done","name":"Concluída","attacks":2,"status":"completed"},
                {"id":"#CURRENT","key":"current","name":"Atual","attacks":1,"status":"running"}]
            """))
        }
        val result = execute("""
            await runRound(options, {
                currentIdentity: async () => '#CURRENT',
                configuredVillages: async () => { throw new Error('Must retain original list'); },
                switchVillage: async () => { throw new Error('Must not revisit completed account'); },
                villageCapacities: async () => ({ elixirCapacity: 25000000 }),
                runVillage: async (parameters, callbacks) => {
                    if (callbacks.attacks !== 1) throw new Error('Lost saved count');
                    return 'Depósito cheio';
                }
            });
        """, previous)
        assertEquals(ScriptSessionState.COMPLETED, result.status.state)
        assertTrue(result.completed)
        val villages = result.state!!.getValue("villages").jsonArray
        assertEquals("Depósito cheio", villages[1].jsonObject.getValue("reason").jsonPrimitive.content)
        assertEquals(2, villages[0].jsonObject.getValue("attacks").jsonPrimitive.int)
    }

    @Test(timeout = 20000) fun transitionFailureKeepsPendingSwitchAndDoesNotCompleteTheVillage() = runBlocking {
        val result = execute("""
            let account = '#INITIAL';
            await runRound(options, {
                currentIdentity: async () => account,
                configuredVillages: async () => [{ id: '#OTHER01', key: 'other', name: 'Outra', capacities: {goldCapacity:25000000}, attacks: 0, status: 'pending' }],
                switchVillage: async () => { throw new Error('Account removed'); },
                villageCapacities: async () => { throw new Error('No capacity read allowed'); },
                runVillage: async () => { throw new Error('No battle allowed'); }
            });
        """)
        assertEquals(ScriptSessionState.FAILED, result.status.state)
        assertEquals("switch", result.state!!.getValue("pending").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("pending", result.state.getValue("villages").jsonArray[0].jsonObject.getValue("status").jsonPrimitive.content)
        assertFalse(result.completed)
    }

    @Test(timeout = 20000) fun incompatibleOptionsFailBeforeDiscoveryOrAnyGesture() = runBlocking {
        val result = execute("await runRound({ ...options, improveWalls: true });")
        assertEquals(ScriptSessionState.FAILED, result.status.state)
        assertNull(result.state)
        val unbounded = execute("await runRound({ ...options, maxBattles: 0 });")
        assertEquals(ScriptSessionState.FAILED, unbounded.status.state)
        assertNull(unbounded.state)
    }

    @Test(timeout = 20000) fun uncertainAttackRequiresAnExplicitResolutionAndNeverStartsAnotherAttack() = runBlocking {
        val previous = buildJsonObject {
            put("version", 1); put("options", options()); put("stage", "ready")
            put("villages", Json.parseToJsonElement("""[{"id":"#CURRENT","key":"current","name":"Atual","attacks":1,"status":"running"}]"""))
            put("pending", Json.parseToJsonElement("""{"kind":"attack","village":"#CURRENT","confirmedBefore":1}"""))
        }
        val result = execute("""
            await runRound(options, { currentIdentity: async () => '#CURRENT',
                runVillage: async () => { throw new Error('Must not attack before resolving uncertainty'); } });
        """, previous, home = true, homeAfterCaptures = 2)
        assertEquals(ScriptSessionState.STOPPED, result.status.state)
        assertEquals(1, result.state!!.getValue("villages").jsonArray.single().jsonObject.getValue("attacks").jsonPrimitive.int)
        assertEquals("attack", result.state.getValue("pending").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(listOf("count", "discard"), result.state.getValue("recovery").jsonObject.getValue("choices").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertFalse(result.completed)
        val answered = JsonObject(result.state + ("recovery" to JsonObject(result.state.getValue("recovery").jsonObject + ("answer" to JsonPrimitive("count")))))
        val continued = execute("""
            await runRound(options, { currentIdentity: async () => '#CURRENT', villageCapacities: async () => ({}),
                runVillage: async (_, callbacks) => {
                    if (callbacks.attacks !== 2) throw new Error('Resolution must count exactly once');
                    return 'Limite atingido';
                } });
        """, answered, home = true)
        assertEquals(ScriptSessionState.COMPLETED, continued.status.state)
        assertTrue(continued.completed)
        assertEquals(2, continued.state!!.getValue("villages").jsonArray.single().jsonObject.getValue("attacks").jsonPrimitive.int)
    }

    @Test(timeout = 20000) fun invalidSavedQueueOrPendingTransitionIsPreservedBeforeAnyAutomation() = runBlocking {
        val valid = buildJsonObject {
            put("version", 1); put("options", options()); put("stage", "ready")
            put("villages", Json.parseToJsonElement("""[{"id":"#CURRENT","key":"current","name":"Atual","attacks":1,"status":"running"}]"""))
        }
        val invalidStates = listOf(
            JsonObject(valid + ("villages" to JsonArray(emptyList()))),
            JsonObject(valid + ("stage" to JsonPrimitive("unknown"))),
            JsonObject(valid + ("stage" to JsonPrimitive("completed"))),
            JsonObject(valid + ("pending" to Json.parseToJsonElement("""{"kind":"unknown","village":"#CURRENT"}"""))),
            JsonObject(valid + ("pending" to Json.parseToJsonElement("""{"kind":"switch","village":"#MISSING"}"""))),
        )
        for (previous in invalidStates) {
            val result = execute("""
                await runRound(options, {
                    currentIdentity: async () => { throw new Error('Invalid progress must not authorize automation'); }
                });
            """, previous)
            assertEquals(ScriptSessionState.FAILED, result.status.state)
            assertTrue(result.status.failure?.message.orEmpty().contains("inválid"))
            assertEquals(previous, result.state)
            assertFalse(result.completed)
        }
    }

    @Test(timeout = 20000) fun rawOcrSeparatesSimilarVillageNamesAndRejectsAmbiguousIdentitiesAndCapacities() = runBlocking {
        val result = execute("""
            const blocks = [
                {text:'MarlonPassoz',left:198,top:41,right:506,bottom:92},
                {text:'RDB4Marlon1',left:263,top:90,right:584,bottom:155},
                {text:'Centrodavila:18',left:193,top:152,right:517,bottom:201},
                {text:'MarlonFull',left:196,top:285,right:439,bottom:336},
                {text:'RDB4Marlon2',left:263,top:336,right:584,bottom:396},
                {text:'Centrodavila:18',left:193,top:395,right:517,bottom:443},
                {text:'FacaloginnoSupercellID',left:235,top:709,right:795,bottom:766}];
            const rows = selectorRows(blocks);
            if (rows.length !== 2 || rows[0].key !== 'MarlonPassoz' || rows[1].key !== 'MarlonFull') throw new Error('Invalid rows');
            if (stableTag([{text:'#GCY20PCPL',confidence:97.4}]) !== '#GCY20PCPL') throw new Error('Invalid tag');
            if (capacityValue([{text:'Máx.: 31 000 000'}], 31000000) !== 31000000) throw new Error('Invalid capacity');
            for (const invalid of [[], [{text:'partial',confidence:99}], [{text:'#TAG0001',confidence:94}],
                [{text:'#TAG0001',confidence:99},{text:'#TAG0002',confidence:99}]]) {
                let failed = false; try { stableTag(invalid); } catch (_) { failed = true; }
                if (!failed) throw new Error('Ambiguous tag accepted');
            }
            await stop('OCR validado');
        """)
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, result.status.state)
    }

    @Test(timeout = 20000) fun capacityAcceptsObservedThousandsSeparatorsButRejectsLettersAndAmbiguousBoxes() = runBlocking {
        val result = execute("""
            for (const text of ['31.000.000','31000:000','31:000:000','31 000 000']) {
                if (capacityNumber([{text,confidence:95}]) !== 31000000) throw new Error('Wrong capacity: '+text);
            }
            if (capacityNumber([{text:'30500-000',confidence:87}]) !== 30500000) throw new Error('Wrong elixir capacity');
            for (const blocks of [[{text:'251000:000',confidence:96}], [{text:'-31000000',confidence:99}], [{text:'31O00000',confidence:99}], [{text:'31000000',confidence:84}],
                [{text:'31000000',confidence:99},{text:'6',confidence:99}]]) {
                let rejected=false;try {capacityNumber(blocks);} catch(_) {rejected=true;}
                if(!rejected) throw new Error('Ambiguous capacity accepted');
            }
        """)
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
    }

    @Test(timeout = 20000) fun pauseAfterAccountConfirmationRejectsBattleEntryBeforeAnyGesture() = runBlocking {
        val result = execute("await openBattle(1);", home = true)
        assertEquals(ScriptSessionState.FAILED, result.status.state)
        assertTrue(result.status.failure?.message.orEmpty().contains("Pausa durante"))
    }

    @Test(timeout = 20000) fun changingAccountBeforeANextAttemptDoesNotAuthorizeAnAttack() = runBlocking {
        val result = execute("""
            let account = '#INITIAL';
            await runRound(options, {
                currentIdentity: async () => account,
                configuredVillages: async () => [{id:account,key:'initial',name:'Inicial',attacks:0,status:'pending'}],
                switchVillage: async () => {}, villageCapacities: async () => ({}),
                runVillage: async (_, callbacks) => {
                    account = '#OTHER01';
                    await callbacks.onAttempt();
                    throw new Error('Incorrect account authorized');
                }
            });
        """)
        assertEquals(ScriptSessionState.FAILED, result.status.state)
        assertTrue(result.status.failure?.message.orEmpty().contains("Conta ou pausa mudou"))
        assertEquals(JsonNull, result.state!!.getValue("pending"))
        assertEquals(0, result.state.getValue("villages").jsonArray[0].jsonObject.getValue("attacks").jsonPrimitive.int)
    }

    private data class Result(val status: ScriptSessionStatus, val state: JsonObject?, val completed: Boolean)
    private fun options(): JsonObject {
        val manifest = Json.parseToJsonElement(resource("manifest.json")).jsonObject
        return JsonObject(manifest.getValue("parameters").jsonObject.mapValues { it.value.jsonObject.getValue("default") } +
            mapOf("goldMinimum" to JsonPrimitive(0), "elixirMinimum" to JsonPrimitive(0), "stopFarm" to JsonPrimitive("nunca"),
                "multivillage" to JsonPrimitive(true), "improveWalls" to JsonPrimitive(false), "maxBattles" to JsonPrimitive(2)))
    }

    @Test(timeout = 20000) fun retryOnTheBattleOrResultScreenConfirmsThePendingAttackExactlyOnce() = runBlocking {
        for (screen in listOf("battle", "result")) {
            val previous = buildJsonObject {
                put("version", 1); put("options", options()); put("stage", "ready")
                put("villages", Json.parseToJsonElement("""[{"id":"#CURRENT","key":"current","name":"Atual","attacks":1,"status":"running"}]"""))
                put("pending", Json.parseToJsonElement("""{"kind":"attack","village":"#CURRENT","confirmedBefore":1}"""))
            }
            val result = execute("""
                let recovered = 0;
                await runRound(options, { currentIdentity: async () => '#CURRENT',
                    runBattle: async () => { recovered++; },
                    runVillage: async (_, callbacks) => {
                        if (recovered !== 1 || callbacks.attacks !== 2) throw new Error('Pending attack not confirmed exactly once');
                        return 'Limite atingido';
                    } });
            """, previous, recoveryScreen = screen)
            assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
            assertEquals(2, result.state!!.getValue("villages").jsonArray.single().jsonObject.getValue("attacks").jsonPrimitive.int)
            assertEquals(JsonNull, result.state.getValue("pending"))
        }
    }

    private suspend fun execute(source: String, previous: JsonObject? = null, home: Boolean = false, homeAfterCaptures: Int = 0,
        recoveryScreen: String? = null): Result = kotlinx.coroutines.coroutineScope {
        var checkpoint = previous
        val store = object : ScriptExecutionState {
            override suspend fun read() = checkpoint
            override suspend fun write(value: JsonObject, completed: Boolean) {
                checkpoint = Json.parseToJsonElement(value.toString()).jsonObject
                // Persist the completion flag independently, as the Android envelope does.
                resultCompleted = completed
            }
            var resultCompleted = false
        }
        var captures = 0
        val backend = object : ScreenAutomationBackend {
            override suspend fun capture(): ScreenSize { captures++; return ScreenSize(2340, 1080) }
            override suspend fun recognize(query: RecognitionQuery) = ScreenRecognition(
                if (query.reference == recoveryScreen || home && captures > homeAfterCaptures && query.reference == "village") RecognitionStatus.FOUND else RecognitionStatus.NOT_FOUND, 99.0)
            override suspend fun tap(position: ScreenPoint, durationMs: Long) { error("Unexpected gesture") }
            override suspend fun tapSequence(steps: List<ScreenTapStep>) { error("Unexpected gesture") }
            override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) { error("Unexpected gesture") }
            override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) { error("Unexpected gesture") }
            override suspend fun gesture(fingers: List<ScreenGestureFinger>) { error("Unexpected gesture") }
            override suspend fun releasePointers() {}
            override suspend fun invalidateObservation() {}
        }
        val files = listOf("spells.js", "rewards.js", "round.js", "villages.js", "farm.js", "battle.js", "walls.js", "screen.js", "profile.js", "policy.js", "configuration.js")
        val entry = """
            import { runRound } from './round.js';
            import { openBattle } from './battle.js';
            import { selectorRows, stableTag, capacityValue, capacityNumber } from './villages.js';
            import { parameters, loadState, stop } from 'klickr';
            const options = await parameters();
            $source
        """.trimIndent()
        val host = ScreenAutomationHost(backend, { true }, startParameters = options(), executionState = store,
            referenceProfile = ScreenProfile.parse(Json.parseToJsonElement(resource("manifest.json")).jsonObject.getValue("screen")))
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        launch { session.run(ScriptProgram("entry.js", files.associateWith(::resource) + ("entry.js" to entry)), host) }.join()
        Result(session.status.value, checkpoint, store.resultCompleted)
    }

    private fun resource(path: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/$path"))
        .bufferedReader().use { it.readText() }
}
