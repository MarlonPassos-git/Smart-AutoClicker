package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VillageSetupIntegrationTest {
    @Test(timeout = 20000)
    fun setupReadsAllThreeCapacitiesBeforeSavingAndRestoresTheInitialAccountWithoutAttacking() = runBlocking {
        val result = execute("""
            let account = '#TAG0001';
            const reads = [];
            await configureVillages({
                currentIdentity: async () => account,
                discoverVillages: async (initial, visit) => {
                    const items = [];
                    for (const id of ['#TAG0001', '#TAG0002']) {
                        account = id;
                        const village = { id, key: id, label: 'Marlon '+id };
                        village.capacities = await visit(village);
                        items.push(village);
                    }
                    account = initial;
                    return items;
                },
                villageCapacities: async () => {
                    reads.push(account);
                    return {goldCapacity: account === '#TAG0001' ? 31000000 : 25000000,
                        elixirCapacity:29000000, darkCapacity:470000};
                }
            });
            if (reads.join(',') !== '#TAG0001,#TAG0002') throw new Error('Did not visit both accounts');
        """)
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
        assertEquals(listOf(31000000, 25000000), result.configuration!!.getValue("items").jsonArray.map {
            it.jsonObject.getValue("capacities").jsonObject.getValue("goldCapacity").jsonPrimitive.int
        })
        assertEquals(1, result.configurationWrites)
        assertEquals(0, result.checkpointWrites)
    }

    @Test(timeout = 20000)
    fun failedSetupKeepsThePreviousCatalogAndRound() = runBlocking {
        val previous = catalog()
        val result = execute("""
            await configureVillages({currentIdentity:async ()=>'#TAG0001',
                discoverVillages: async (_, visit) => {
                    await visit({id:'#TAG0001',key:'first',label:'Primeira'});
                    throw new Error('Capacity reading failed on second village');
                }, villageCapacities:async ()=>({goldCapacity:1,elixirCapacity:2,darkCapacity:3})});
        """, previous)
        assertEquals(ScriptSessionState.FAILED, result.status.state)
        assertEquals(previous, result.configuration)
        assertEquals(0, result.configurationWrites)
        assertEquals(0, result.checkpointWrites)
    }

    @Test(timeout = 20000)
    fun newRoundUsesTheSelectedSavedAccountsAndTheirCapacitiesStartingWithTheOpenVillage() = runBlocking {
        val result = execute("""
            let account = '#TAG0002';
            const visited = [];
            const options = {villages:['#TAG0001','#TAG0002'], multivillage:true, improveWalls:false,
                stopFarm:'elixir negro cheio',maxBattles:0,targetDestruction:50,maxWallBatch:1,
                goldMinimum:0,elixirMinimum:0,goldReserve:0,elixirReserve:0};
            await runRound(options, {configuredVillages, currentIdentity:async ()=>account,
                switchVillage:async village=>{account=village.id;},
                runVillage:async options=>{
                    if (options.improveWalls || options.darkCapacity !== (account === '#TAG0001' ? 470000 : 410000)) {
                        throw new Error('Lost per-village capacities or enabled walls');
                    }
                    visited.push(account); return 'Elixir negro cheio';
                }});
            if (visited.join(',') !== '#TAG0002,#TAG0001') throw new Error('Wrong village selection/order');
        """, catalog())
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
        assertEquals(0, result.configurationWrites)
        assertTrue(result.checkpointWrites > 0)
    }

    @Test(timeout = 20000)
    fun missingCatalogCapacityOrRemovedTagFailsBeforeAnyGesture() = runBlocking {
        val incompatible = JsonObject(catalog() + ("profile" to JsonPrimitive("obsolete")))
        val first = catalog().getValue("items").jsonArray.first().jsonObject
        val missingCapacity = JsonObject(first + ("capacities" to JsonObject(first.getValue("capacities").jsonObject - "darkCapacity")))
        val incomplete = JsonObject(catalog() + ("items" to JsonArray(listOf(missingCapacity))))
        for (configuration in listOf(null, incompatible, incomplete)) {
            val result = execute("await configuredVillages({villages:['#TAG0001']});", configuration)
            assertEquals(ScriptSessionState.FAILED, result.status.state)
            assertEquals(0, result.configurationWrites)
            assertEquals(0, result.checkpointWrites)
        }
        val result = execute("await configuredVillages({villages:['#REMOVED']});", catalog())
        assertEquals(ScriptSessionState.FAILED, result.status.state)
        assertTrue(result.status.failure?.message.orEmpty().contains("ausente"))
    }

    @Test(timeout = 20000)
    fun offerBalancesAboveStorageCapacityCountAsFullWithoutChangingCapacity() = runBlocking {
        val result = execute("""
            const options = {goldCapacity:25000000,elixirCapacity:25000000,darkCapacity:410000};
            const balances = {gold:38000000,elixir:1,dark:600000};
            if (resourceRule('ouro cheio',balances,options) !== true) throw new Error('Offer overflow was not full');
            if (resourceRule('elixir negro cheio',balances,options) !== true) throw new Error('Dark overflow was not full');
            if (resourceRule('qualquer depósito cheio',balances,options) !== true) throw new Error('Ignored full storage');
            if (resourceRule('todos cheios',balances,options) !== false) throw new Error('Incomplete elixir became full');
            if (options.goldCapacity !== 25000000 || options.darkCapacity !== 410000) throw new Error('Balance replaced capacity');
        """)
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
    }

    @Test(timeout = 20000)
    fun darkElixirParticipatesInAnyAndAllFullRulesAndUnreadableValuesStayUnknown() = runBlocking {
        val result = execute("""
            const options = {goldCapacity:100,elixirCapacity:200,darkCapacity:300};
            if (resourceRule('elixir negro cheio',{dark:300},options) !== true) throw new Error('Ignored dark elixir');
            if (resourceRule('qualquer depósito cheio',{gold:1,elixir:2,dark:300},options) !== true) throw new Error('Ignored full dark storage');
            if (resourceRule('todos cheios',{gold:100,elixir:200,dark:299},options) !== false) throw new Error('Completed too early');
            if (resourceRule('todos cheios',{gold:100,elixir:200},options) !== undefined) throw new Error('Unreadable dark elixir became full');
            if (resourceRule('todos cheios',{gold:100,elixir:200,dark:300},options) !== true) throw new Error('All full not detected');
        """)
        assertEquals(result.status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, result.status.state)
    }

    private fun catalog() = Json.parseToJsonElement("""{
        "formatVersion":1,"profile":"sm-s916b-2340x1080-v1","items":[
            {"id":"#TAG0001","key":"first","label":"Marlon ¹","capacities":{"goldCapacity":31000000,"elixirCapacity":29000000,"darkCapacity":470000}},
            {"id":"#TAG0002","key":"second","label":"Marlon ²","capacities":{"goldCapacity":25000000,"elixirCapacity":25000000,"darkCapacity":410000}}
        ]}
    """).jsonObject

    private data class Result(val status: ScriptSessionStatus, val configuration: JsonObject?, val configurationWrites: Int, val checkpointWrites: Int)

    private suspend fun execute(source: String, previous: JsonObject? = null): Result = kotlinx.coroutines.coroutineScope {
        var configuration = previous
        var configurationWrites = 0
        var checkpointWrites = 0
        var checkpoint: JsonObject? = null
        val store = object : ScriptExecutionState {
            override suspend fun read() = checkpoint
            override suspend fun write(value: JsonObject, completed: Boolean) { checkpoint = value; checkpointWrites++ }
            override suspend fun readConfiguration() = configuration
            override suspend fun writeConfiguration(value: JsonObject) { configuration = value; configurationWrites++ }
        }
        val backend = object : ScreenAutomationBackend {
            override suspend fun capture() = ScreenSize(2340, 1080)
            override suspend fun recognize(query: RecognitionQuery): ScreenRecognition = error("Unexpected recognition")
            override suspend fun tap(position: ScreenPoint, durationMs: Long) { error("Unexpected gesture") }
            override suspend fun tapSequence(steps: List<ScreenTapStep>) { error("Unexpected gesture") }
            override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) { error("Unexpected gesture") }
            override suspend fun gesture(fingers: List<ScreenGestureFinger>) { error("Unexpected gesture") }
            override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) { error("Unexpected gesture") }
            override suspend fun releasePointers() {}
            override suspend fun invalidateObservation() {}
        }
        val files = listOf("spells.js", "rewards.js", "configuration.js", "round.js", "villages.js", "farm.js", "battle.js", "walls.js", "screen.js", "profile.js", "policy.js")
        val sources = files.associateWith { name ->
            checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/$name")).bufferedReader().use { it.readText() }
        } + ("test.js" to """
            import {configureVillages, configuredVillages} from './configuration.js';
            import {runRound} from './round.js';
            import {resourceRule} from './policy.js';
            $source
        """.trimIndent())
        val host = ScreenAutomationHost(backend, { true }, executionState = store)
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        launch { session.run(ScriptProgram("test.js", sources), host) }.join()
        Result(session.status.value, configuration, configurationWrites, checkpointWrites)
    }
}
