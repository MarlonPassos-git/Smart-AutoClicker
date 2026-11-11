package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FarmExampleIntegrationTest {
    @Test(timeout = 20000)
    fun anIgnoredVillageOpeningTapIsRetriedBeforeStartingOneBattle() = runBlocking {
        val backend = BattleBackend("destruição").apply { ignoredOpeningTaps = 1 }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(2, backend.openingTaps)
        assertEquals(25, backend.troopsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun repeatedIgnoredOpeningTapsStopBeforeMatchmaking() = runBlocking {
        val backend = BattleBackend("destruição").apply { ignoredOpeningTaps = 3 }
        val status = execute(backend)
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertEquals(3, backend.openingTaps)
        assertTrue(status.reason!!.contains("três tentativas"))
        assertEquals(0, backend.troopsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun anUnexpectedScreenPreventsRepeatingTheOpeningTap() = runBlocking {
        val backend = BattleBackend("destruição").apply {
            ignoredOpeningTaps = 1
            screenAfterIgnoredOpening = "unexpected"
        }
        val status = execute(backend)
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertEquals(1, backend.openingTaps)
        assertTrue(status.reason!!.contains("vila antes da batalha"))
        assertEquals(0, backend.troopsDeployed)
    }

    @Test(timeout = 15000)
    fun resourceAndBattleDecisionsDoNotTurnAnUnreadableNumberIntoZero() = runBlocking {
        val program = sources().copy(entry = "policy-test.js", sources = sources().sources + ("policy-test.js" to """
            import { resourceRule, battleFinished, canReserve, nextBurst } from './policy.js';
            const options = { goldCapacity: 100, elixirCapacity: 200, targetDestruction: 50, finishMode: 'saque total' };
            if (resourceRule('ambos cheios', { gold: 100 }, options) !== undefined) throw new Error('Missing elixir became zero');
            if (resourceRule('qualquer depósito cheio', { gold: 100 }, options) !== true) throw new Error('Known full gold was ignored');
            if (battleFinished(options, 60, 0, undefined)) throw new Error('Missing loot became zero');
            if (!battleFinished(options, 60, 0, 0)) throw new Error('Two valid zero readings were ignored');
            if (canReserve(undefined, 10, 1) || !canReserve(11, 10, 1) || canReserve(10, 10, 1)) throw new Error('Reserve boundary is wrong');
            const consume = amount => { const bursts = []; let attempted = 0;
                while (amount > 0) { const count = nextBurst(amount, attempted); bursts.push(count); attempted += count; amount -= count; }
                return bursts.join(','); };
            if (consume(25) !== '16,8,1' || consume(29) !== '16,8,4,1' || nextBurst(1,600) !== 0) throw new Error('Troop budget is wrong');
        """.trimIndent()))
        val runtime = QuickJsScriptRuntime(Dispatchers.Default)
        runtime.validate(program)
        runtime.execute(program) { error("Pure policy must not call Android") }
    }

    @Test(timeout = 20000)
    fun farmDeploysTheReadQuantityAndConfirmsDestructionExitBeforeReturningToTheVillage() = runBlocking {
        val backend = BattleBackend("destruição")
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(25, backend.troopsDeployed)
        assertEquals(1, backend.heroAbilities)
        assertEquals(0, backend.spellsDeployed)
        assertEquals(1, backend.exitRequests)
        assertEquals(1, backend.exitConfirmations)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun lootModeContinuesAboveTheDestructionTargetUntilBothLootReadingsAreZero() = runBlocking {
        val backend = BattleBackend("saque total")
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(25, backend.troopsDeployed)
        assertEquals(1, backend.exitConfirmations)
        assertTrue(backend.heroAbilities > 0)
    }

    @Test(timeout = 20000)
    fun naturalEndDeploysSpellsAndReturnsWithoutRequestingWithdrawal() = runBlocking {
        val backend = BattleBackend("fim natural")
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(25, backend.troopsDeployed)
        assertEquals(2, backend.spellsDeployed)
        assertEquals(0, backend.exitRequests)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 15000)
    fun zeroBalanceTriggersTheConfiguredMinimumBeforeAnyGesture() = runBlocking {
        val backend = BattleBackend("destruição")
        val status = execute(backend, buildJsonObject { put("goldMinimum", 1) })
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Saldo mínimo de gold"))
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
    }

    private suspend fun execute(backend: BattleBackend, overrides: JsonObject = JsonObject(emptyMap())): ScriptSessionStatus = coroutineScope {
        val manifest = Json.parseToJsonElement(resource("manifest.json")).jsonObject
        val defaults = manifest.getValue("parameters").jsonObject.mapValues { it.value.jsonObject.getValue("default") }
        val options = JsonObject(defaults + mapOf("improveWalls" to JsonPrimitive(false), "stopFarm" to JsonPrimitive("nunca"),
            "maxBattles" to JsonPrimitive(1), "finishMode" to JsonPrimitive(backend.finishMode)) + overrides)
        val host = ScreenAutomationHost(backend, { false }, backend.clock::get, options,
            referenceProfile = ScreenProfile.parse(manifest.getValue("screen")))
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        launch { session.run(sources(), host) }.join()
        session.status.value
    }

    private fun sources(): ScriptProgram {
        val files = listOf("entry.js", "farm.js", "battle.js", "walls.js", "screen.js", "policy.js", "profile.js")
        return ScriptProgram("entry.js", files.associateWith(::resource))
    }

    private fun resource(path: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/$path"))
        .bufferedReader().use { it.readText() }

    private class BattleBackend(val finishMode: String) : ScreenAutomationBackend {
        var ignoredOpeningTaps = 0
        var openingTaps = 0
        var screenAfterIgnoredOpening = "village"
        val clock = AtomicLong()
        val taps = mutableListOf<ScreenPoint>()
        var screen = "village"
        var troopsDeployed = 0
        var heroAbilities = 0
        var spellsDeployed = 0
        var exitRequests = 0
        var exitConfirmations = 0
        private var selected: String? = null
        private var heroDeployed = false
        override suspend fun capture(): ScreenSize { clock.addAndGet(2_000_000_000); return ScreenSize(2340, 1080) }
        override suspend fun recognize(query: RecognitionQuery): ScreenRecognition {
            if (query.kind == RecognitionKind.NUMBER) {
                val value = when {
                    query.reference == "quantity" && screen == "battle" && troopsDeployed < 25 -> (25 - troopsDeployed).toDouble()
                    query.region == ScreenRegion(2140, 815, 2250, 880) && screen == "battle" ->
                        if (finishMode == "saque total" || heroDeployed) 60.0 else 20.0
                    query.region == ScreenRegion(170, 130, 410, 182) || query.region == ScreenRegion(170, 188, 410, 240) ->
                        if (heroDeployed) 0.0 else 10.0
                    query.region == ScreenRegion(1910, 25, 2180, 90) -> 0.0
                    else -> null
                }
                return if (value == null) ScreenRecognition(RecognitionStatus.INVALID_READING, 0.0)
                    else ScreenRecognition(RecognitionStatus.FOUND, 100.0, ScreenPoint(200, 945), value)
            }
            val recognized = when (query.reference) {
                "village", "villageText" -> screen == "village"
                "search", "searchText" -> screen == "search"
                "attack" -> screen == "attack"
                "opponent" -> screen == "opponent"
                "battle" -> screen == "battle" || screen == "opponent"
                "exitConfirm" -> screen == "exitConfirm"
                "result", "resultText" -> screen == "result"
                "hero1" -> screen == "battle" && !heroDeployed
                "spell" -> screen == "battle" && heroDeployed
                else -> false
            }
            val position = when (query.reference) {
                "hero1" -> ScreenPoint(1500, 990)
                "spell" -> ScreenPoint(1000, 1000)
                "exitConfirm" -> ScreenPoint(1380, 700)
                else -> ScreenPoint((query.region.left + query.region.right) / 2, (query.region.top + query.region.bottom) / 2)
            }
            return ScreenRecognition(if (recognized) RecognitionStatus.FOUND else RecognitionStatus.NOT_FOUND, 100.0,
                if (recognized) position else null)
        }
        override suspend fun tap(position: ScreenPoint, durationMs: Long) {
            taps.add(position)
            when (screen) {
                "village" -> {
                    openingTaps++
                    if (ignoredOpeningTaps > 0) {
                        ignoredOpeningTaps--
                        screen = screenAfterIgnoredOpening
                    } else screen = "search"
                }
                "search" -> screen = "attack"
                "attack" -> screen = "opponent"
                "exitConfirm" -> { screen = "result"; exitConfirmations++ }
                "result" -> screen = "village"
                "battle" -> when (position) {
                    ScreenPoint(210, 845) -> { screen = "exitConfirm"; exitRequests++ }
                    ScreenPoint(200, 945) -> selected = "troops"
                    ScreenPoint(1500, 990) -> if (heroDeployed) heroAbilities++ else selected = "hero"
                    ScreenPoint(1000, 1000) -> selected = "spell"
                    else -> when (selected) {
                        "troops" -> troopsDeployed++
                        "hero" -> heroDeployed = true
                        "spell" -> { spellsDeployed++; if (finishMode == "fim natural" && spellsDeployed == 2) screen = "result" }
                    }
                }
            }
        }
        override suspend fun tapSequence(steps: List<ScreenTapStep>) { steps.forEach { tap(it.position, it.pressMs) } }
        override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) {
            if (screen == "opponent") screen = "battle"
        }
        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) = Unit
        override suspend fun gesture(fingers: List<ScreenGestureFinger>) = Unit
        override suspend fun releasePointers() = Unit
        override suspend fun invalidateObservation() = Unit
    }
}
