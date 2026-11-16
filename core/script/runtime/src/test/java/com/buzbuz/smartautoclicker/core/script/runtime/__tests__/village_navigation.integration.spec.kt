package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VillageNavigationIntegrationTest {
    @Test(timeout = 20000) fun theSettingsTabWithTheObservedCv19ConfidenceStillAllowsStrictTagConfirmation() = runBlocking {
        val backend = SelectorBackend().apply { settingsConfidence = 84.927139 }
        val status = execute(backend, "if (await currentIdentity() !== '#TAG0002') throw new Error('Wrong account');")
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, status.state)
        assertEquals("home", backend.screen)
        assertTrue(backend.selections.isEmpty())
    }

    @Test(timeout = 20000) fun discoveryStartsWithTheOpenAccountConfirmsTagsAndDeduplicatesScrollOverlap() = runBlocking {
        val backend = SelectorBackend()
        val status = execute(backend, """
            const initial = await currentIdentity();
            const villages = await discoverVillages(initial);
            if (villages.map(item=>item.id).join(',') !== '#TAG0002,#TAG0001,#TAG0003') throw new Error('Wrong identity/order: '+JSON.stringify(villages));
            const capacity = await villageCapacities({stopFarm:'ambos cheios'});
            if (capacity.goldCapacity !== 31000000 || capacity.elixirCapacity !== 25000000) throw new Error('Wrong per-village capacities');
        """)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, status.state)
        assertEquals(1, backend.account)
        assertEquals("home", backend.screen)
        assertEquals(listOf(0, 1, 1, 2, 1), backend.selections)
        assertTrue(backend.upwardScrolls > 0)
        assertFalse(backend.loginSelected)
    }

    @Test(timeout = 20000) fun removedAccountAndWrongTagNeverAuthorizeANextAction() = runBlocking {
        val removed = execute(SelectorBackend(), "await switchVillage({id:'#MISSING',key:'Removed'});")
        assertEquals(ScriptSessionState.FAILED, removed.state)
        assertTrue(removed.failure?.message.orEmpty().contains("removida"))
        val backend = SelectorBackend()
        val wrong = execute(backend, "await switchVillage({id:'#WRONG01',key:'First'});")
        assertEquals(ScriptSessionState.FAILED, wrong.state)
        assertTrue(wrong.failure?.message.orEmpty().contains("nenhum ataque"))
        assertEquals(listOf(0), backend.selections)
    }

    @Test(timeout = 20000) fun ignoredProfileTapIsRetriedOnlyAfterRecognizingHomeAgain() = runBlocking {
        val backend = SelectorBackend(ignoredProfileTaps = 1)
        val status = execute(backend, "if (await currentIdentity() !== '#TAG0002') throw new Error('Wrong account');")
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, status.state)
        assertEquals(0, backend.ignoredProfileTaps)
        assertEquals("home", backend.screen)
    }

    @Test(timeout = 20000) fun alreadyOpenSettingsAndAnIgnoredCloseTapAreConfirmedBeforeContinuing() = runBlocking {
        val backend = SelectorBackend().apply { screen = "settings"; ignoredCloseTaps = 1 }
        val status = execute(backend, "if (await currentIdentity() !== '#TAG0002') throw new Error('Wrong account');")
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, status.state)
        assertEquals("home", backend.screen)
        assertEquals(0, backend.ignoredCloseTaps)
    }

    @Test(timeout = 20000) fun rewardArrivingAfterHomeIsDismissedBeforeOpeningTheProfile() = runBlocking {
        val backend = SelectorBackend().apply { lateReward = true }
        val status = execute(backend, "if (await currentIdentity() !== '#TAG0002') throw new Error('Wrong account');")
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, status.state)
        assertFalse(backend.lateReward)
        assertEquals("home", backend.screen)
    }

    @Test(timeout = 20000) fun capacityWaitsForTwoValidReadingsAfterTooltipAnimation() = runBlocking {
        val backend = SelectorBackend().apply { invalidCapacityReadings = 2 }
        val status = execute(backend, "if ((await villageCapacities({stopFarm:'ouro cheio'})).goldCapacity !== 31000000) throw new Error('Invalid capacity');")
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, status.state)
        assertEquals(0, backend.invalidCapacityReadings)
        assertEquals("home", backend.screen)
    }

    @Test(timeout = 20000) fun disagreeingNumericCropsNeverAuthorizeACapacity() = runBlocking {
        val backend = SelectorBackend().apply { disagreeingCapacity = true }
        val status = execute(backend, "await villageCapacities({stopFarm:'ouro cheio'});")
        assertEquals(ScriptSessionState.FAILED, status.state)
        assertTrue(status.failure?.message.orEmpty().contains("sem duas leituras válidas"))
    }

    private suspend fun execute(backend: SelectorBackend, source: String): ScriptSessionStatus = kotlinx.coroutines.coroutineScope {
        val manifest = Json.parseToJsonElement(resource("manifest.json")).jsonObject
        var elapsedNanos = 0L
        val host = ScreenAutomationHost(backend, { true }, monotonicNanos = { elapsedNanos }, referenceProfile = ScreenProfile.parse(manifest.getValue("screen")))
        // Navigation is deterministic; operation-clock/pause timing has separate host/session coverage.
        val navigationHost = object : SessionAutomationHost by host {
            override suspend fun call(requestJson: String): String =
                when (Json.parseToJsonElement(requestJson).jsonObject["operation"]?.jsonPrimitive?.content) {
                    "sleep" -> { elapsedNanos += 150_000_000L; "{}" }
                    else -> host.call(requestJson)
                }
        }
        val files = listOf("spells.js", "rewards.js", "villages.js", "screen.js", "profile.js", "policy.js")
        val entry = "import { currentIdentity, discoverVillages, switchVillage, villageCapacities } from './villages.js';\n$source"
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        launch { session.run(ScriptProgram("entry.js", files.associateWith(::resource) + ("entry.js" to entry)), navigationHost) }.join()
        session.status.value
    }

    private fun resource(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/$name"))
        .bufferedReader().use { it.readText() }

    private class SelectorBackend(var ignoredProfileTaps: Int = 0) : ScreenAutomationBackend {
        var settingsConfidence = 99.0
        var disagreeingCapacity = false
        var invalidCapacityReadings = 0
        var lateReward = false
        var ignoredCloseTaps = 0
        var screen = "home"
        var account = 1
        var page = 1 // Reopening the selector may retain a previous scroll position.
        var tooltip: String? = null
        var upwardScrolls = 0
        var loginSelected = false
        val selections = mutableListOf<Int>()
        private val names = listOf("First", "Second", "Third")
        private val pages = listOf(listOf(0, 1), listOf(1, 2))
        private var loadingCaptures = 0
        override suspend fun capture(): ScreenSize {
            if (loadingCaptures > 0 && --loadingCaptures == 0) screen = "home"
            return ScreenSize(2340, 1080)
        }
        override suspend fun recognize(query: RecognitionQuery): ScreenRecognition {
            val found = when (query.reference) {
                "village", "villageText" -> screen == "home" || screen == "settings" // Battle button remains visible behind the profile.
                "accountSettingsTitle" -> screen == "settings"
                "accountSelectorTitle" -> screen == "selector"
                else -> false
            }
            return ScreenRecognition(if (found) RecognitionStatus.FOUND else RecognitionStatus.NOT_FOUND, 99.0)
        }
        private fun block(text: String, x: Int, y: Int, width: Int = 300, height: Int = 40) =
            ScreenText(text, 99.0, ScreenRegion(x, y, x + width, y + height))
        override suspend fun readText(region: ScreenRegion, model: String, threshold: Int, maxResults: Int): List<ScreenText> = (when {
            screen == "settings" && region.top == 30 -> listOf(block("CoNfiguRaces", 1620, 60, 200, 50).copy(confidence = settingsConfidence))
            screen == "settings" && region.left == 1040 -> listOf(block("#TAG000${account + 1}", 1080, 210))
            screen == "selector" && region.top == 50 -> listOf(block("TROCAR DE ID", 1600, 100))
            screen == "selector" && region.left == 1140 -> buildList {
                pages[page].forEachIndexed { index, account ->
                    val top = 250 + index * 250
                    add(block(names[account], 1350, top, 300, 45))
                    add(block("RDB Marlon ${account + 1}", 1420, top + 60))
                    add(block("Centro da vila:18", 1350, top + 120))
                }
                if (page == 1) add(block("Faça login no Supercell ID", 1350, 900))
            }
            screen == "home" && lateReward && region.left == 650 -> listOf(
                block("RecoMPeNsa", 830, 310, 395, 50).copy(confidence = 79.6),
                block("Recebida", 1225, 308, 290, 48), block("OK", 1100, 670, 150, 60))
            screen == "home" && tooltip != null && region.right - region.left < 100 ->
                listOf(block("Máx.", region.left, region.top, region.right - region.left, region.bottom - region.top))
            screen == "home" && tooltip != null && region.left >= 2017 -> {
                if (invalidCapacityReadings > 0) { invalidCapacityReadings--; emptyList() }
                else listOf(block(if (disagreeingCapacity && region.top <= 110) "31000:006"
                    else if (tooltip == "gold") "31000:000" else "25:000:000", region.left, region.top, 200, 40))
            }
            screen == "home" && tooltip != null -> listOf(block("Mäx.231.000.000", 1940, 115))
            else -> emptyList()
        }).filter { it.confidence >= threshold }
        override suspend fun tap(position: ScreenPoint, durationMs: Long) {
            when {
                screen == "home" && lateReward && position.y > 600 -> lateReward = false
                screen == "home" && lateReward -> error("Reward must be dismissed before another gesture")
                screen == "home" && position.x == 180 -> if (ignoredProfileTaps > 0) ignoredProfileTaps-- else screen = "settings"
                screen == "home" && position.x == 2100 -> tooltip = if (position.y == 50) "gold" else "elixir"
                screen == "home" && position.x == 1800 -> tooltip = null
                screen == "settings" && position.x > 1850 -> if (ignoredCloseTaps > 0) ignoredCloseTaps-- else screen = "home"
                screen == "settings" && position.x == 1475 -> screen = "selector"
                screen == "settings" && position.y < 150 -> Unit // Already on the Settings tab.
                screen == "selector" && position.x == 173 -> screen = "settings"
                screen == "selector" -> {
                    if (position.y >= 850) { loginSelected = true; error("Login must never be selected") }
                    val index = if (position.y < 450) 0 else 1
                    account = pages[page][index]
                    selections.add(account)
                    screen = "settings"
                    loadingCaptures = 3 // Closing the selector does not immediately dismiss the profile underneath.
                }
                else -> error("Unexpected gesture $screen $position")
            }
        }
        override suspend fun tapSequence(steps: List<ScreenTapStep>) { steps.forEach { tap(it.position, it.pressMs) } }
        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) {
            check(screen == "selector")
            if (to.y > from.y) { page = (page - 1).coerceAtLeast(0); upwardScrolls++ }
            else page = (page + 1).coerceAtMost(1)
        }
        override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) { error("Unexpected zoom") }
        override suspend fun gesture(fingers: List<ScreenGestureFinger>) { error("Unexpected gesture") }
        override suspend fun releasePointers() {}
        override suspend fun invalidateObservation() {}
    }
}
