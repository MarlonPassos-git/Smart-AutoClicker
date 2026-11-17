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
    fun aSpellReadFromTheWideStripDoesNotSkipRemainingTroops() = runBlocking {
        val backend = BattleBackend("fim natural").apply { wideCounterMissesTroops = true }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(25, backend.troopsDeployed)
        assertEquals("troops", backend.deploymentOrder.first())
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun smallHeaderWindowsRecoverTroopsWhenBothQuantityReadsMissThem() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            wideCounterMissesTroops = true
            scopedCounterMissesTroops = true
        }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(25, backend.troopsDeployed)
        assertEquals(2, backend.spellsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun matchmakingLongerThanOneMinuteStillCompletesTheBattle() = runBlocking {
        val backend = BattleBackend("fim natural").apply { matchmakingDelayMs = 70000 }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals("village", backend.screen)
        assertEquals(25, backend.troopsDeployed)
        assertEquals(0, backend.tapsDuringMatchmaking)
    }

    @Test(timeout = 20000)
    fun anUnresolvedSearchStopsWithoutDeployingOrCountingAnAttack() = runBlocking {
        val backend = BattleBackend("fim natural").apply { matchmakingDelayMs = 240000 }
        val status = execute(backend)
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("oponente encontrado"))
        assertEquals("matchmaking", backend.screen)
        assertEquals(0, backend.troopsDeployed)
        assertEquals(0, backend.tapsDuringMatchmaking)
    }

    @Test(timeout = 15000)
    fun aConfirmedBattleLimitDoesNotRequireAnotherBalanceRead() = runBlocking {
        val original = sources()
        val program = original.copy(entry = "balance-limit.js", sources = original.sources + ("balance-limit.js" to """
            import {runVillage} from './farm.js';
            const options={multivillage:false,improveWalls:false,stopFarm:'nunca',targetDestruction:50,
                maxWallBatch:50,maxBattles:6,goldReserve:0,elixirReserve:0,goldMinimum:0,elixirMinimum:0};
            const reason=await runVillage(options,{attacks:6});
            if (!reason.includes('Limite de 6')) throw new Error('Confirmed limit lost');
        """.trimIndent()))
        QuickJsScriptRuntime(Dispatchers.Default).execute(program) { error("Completed attacks must not trigger another observation") }
    }

    @Test(timeout = 15000)
    fun balanceConsensusRejectsMissingTiedAndInvalidReadings() = runBlocking {
        val original = sources()
        val program = original.copy(entry = "balance-policy.js", sources = original.sources + ("balance-policy.js" to """
            import {balanceConsensus} from './farm.js';
            for (const [values,expected] of [[[337221,undefined,337221],337221],[[0,0],0],[[1,2,2],2]])
                if (balanceConsensus(values)!==expected) throw new Error('Wrong consensus');
            for (const values of [[],[337221],[1,1,2,2],[-1,-1],[NaN,NaN]])
                if (balanceConsensus(values)!==undefined) throw new Error('Ambiguous balance accepted');
        """.trimIndent()))
        QuickJsScriptRuntime(Dispatchers.Default).execute(program) { error("Consensus must not call Android") }
    }

    @Test(timeout = 20000)
    fun clippedCounterHeadersStillSelectInsideTheSpellCard() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            clippedSpellHeader = true
            spellAmounts = mutableListOf(11)
        }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(11, backend.spellsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun battlefieldLevelsDoNotBecomeSpellCards() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            battlefieldLevelVisible = true
            spellAmounts = mutableListOf(3, 5)
        }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(listOf(0, 0), backend.spellAmounts)
        assertEquals(8, backend.spellsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun anExplicitZeroSpellCounterDoesNotProduceACast() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            spellAmounts = mutableListOf(0)
            naturalEndAfterCaptures = 4
        }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(0, backend.spellsDeployed)
        assertEquals(0, backend.excessSpellTaps)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 15000)
    fun spellCountersPreserveZeroAndRejectUnreadableValues() = runBlocking {
        val original = sources()
        val program = original.copy(entry = "spell-policy.js", sources = original.sources + ("spell-policy.js" to """
            import {spellQuantity} from './spells.js';
            for (const [text, expected] of [['x0',0],['X1',1],['× 5',5],['x11',11],['xIl',11],['9',9]]) {
                if (spellQuantity(text) !== expected) throw new Error('Wrong quantity: '+text);
            }
            for (const text of ['', 'x?', '-1', '1.5', 'x99999999999999999']) {
                if (spellQuantity(text) !== undefined) throw new Error('Invalid quantity accepted: '+text);
            }
        """.trimIndent()))
        QuickJsScriptRuntime(Dispatchers.Default).execute(program) { error("Quantity policy must not call Android") }
    }

    @Test(timeout = 20000)
    fun aSpellCounterThatDoesNotDecreaseFinishesTheBattleWithoutUnboundedCasting() = runBlocking {
        val backend = BattleBackend("fim natural").apply { ignoreSpellCasts = true; naturalEndAfterCaptures = 40 }
        val status = execute(backend)
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(0, backend.spellsDeployed)
        assertEquals(12, backend.spellCastAttempts)
        assertEquals(1, backend.heroAbilities)
        assertEquals(1, backend.openingTaps)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun anUnreadableCounterAfterCastingIsNeverConsideredEmpty() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            spellAmounts = mutableListOf(5)
            hideSpellCountersAfterCasting = true
            naturalEndAfterCaptures = 40
        }
        val status = execute(backend)
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(2, backend.spellsDeployed)
        assertEquals(listOf(3), backend.spellAmounts)
        assertEquals(1, backend.openingTaps)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun spellsOfDifferentTypesAndOddQuantitiesAreAllUsedWithoutMatchingAPortrait() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            spellAmounts = mutableListOf(3, 1, 5)
            spellImageVisible = false
            naturalEndAfterCaptures = 10
        }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(listOf(0, 0, 0), backend.spellAmounts)
        assertEquals(9, backend.spellsDeployed)
        assertEquals(0, backend.excessSpellTaps)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun anAlreadyOpenedChestIsFinishedBeforeStartingTheNextBattle() = runBlocking {
        val backend = BattleBackend("destruição").apply { screen = "chest"; chestStrikes = 2 }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(0, backend.starBonusClaims)
        assertEquals(4, backend.chestStrikes)
        assertEquals(1, backend.chestConfirmations)
        assertEquals(1, backend.openingTaps)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun anAlreadyRevealedRewardIsConfirmedBeforeStartingTheNextBattle() = runBlocking {
        val backend = BattleBackend("destruição").apply { screen = "reward" }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(0, backend.chestStrikes)
        assertEquals(1, backend.chestConfirmations)
        assertEquals(1, backend.openingTaps)
    }

    @Test(timeout = 20000)
    fun aChestThatDoesNotOpenStopsWithoutStartingAnotherBattle() = runBlocking {
        val backend = BattleBackend("destruição").apply { screen = "chest"; chestStalled = true }
        val status = execute(backend)
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("baú"))
        assertEquals(12, backend.chestStrikes)
        assertEquals(0, backend.openingTaps)
        assertEquals(0, backend.troopsDeployed)
    }

    @Test(timeout = 20000)
    fun starBonusIsClaimedBeforeConfirmingTheReturnFromBattle() = runBlocking {
        val backend = BattleBackend("destruição").apply { showStarBonusAfterBattle = true }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(1, backend.starBonusClaims)
        assertEquals(4, backend.chestStrikes)
        assertEquals(1, backend.chestConfirmations)
        assertEquals(0, backend.tapsBehindStarBonus)
        assertFalse(backend.starBonusVisible)
    }

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
            for (const total of [1, 9, 25, 29, 599, 600]) {
                let attempted = 0;
                while (attempted < total) {
                    const count = nextBurst(total-attempted, attempted);
                    if (count < 1 || count > 16 || count > total-attempted) throw new Error('Invalid burst');
                    attempted += count;
                }
                if (attempted !== total) throw new Error('Troop budget is wrong');
            }
            if (nextBurst(10,599) !== 1 || nextBurst(1,600) !== 0) throw new Error('Safety limit exceeded');
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

    @Test(timeout = 20000)
    fun troopsAndHeroesAreDeployedBeforeTheFirstSpell() = runBlocking {
        val backend = BattleBackend("fim natural", heroCount = 6)
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertEquals(25, backend.troopsDeployed)
        assertEquals(6, backend.heroAbilities)
        assertEquals(2, backend.spellsDeployed)
        assertEquals(listOf("troops", "heroes", "spells"), backend.deploymentOrder.distinct())
        assertTrue(backend.deploymentOrder.lastIndexOf("troops") < backend.deploymentOrder.indexOf("heroes"))
        assertTrue(backend.deploymentOrder.lastIndexOf("heroes") < backend.deploymentOrder.indexOf("spells"))
    }

    @Test(timeout = 20000)
    fun collapsedElevenTroopCounterIsVerifiedBeforeRetryBudgetRunsOut() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            initialTroops = 27
            collapseElevenTroops = true
        }
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(27, backend.troopsDeployed)
        assertEquals(1, backend.heroAbilities)
        assertEquals(2, backend.spellsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun aRealSingleTroopIsNotInflatedByAnElevenSpellCounter() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            initialTroops = 17
            spellAmounts = mutableListOf(11)
        }
        val status = execute(backend)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(17, backend.troopsDeployed)
        assertEquals(11, backend.spellsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun persistentTroopFailureStillDeploysHeroesAndSpellsAndConfirmsTheBattle() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            initialTroops = 11
            collapseElevenTroops = true
            ignoreTroopCasts = true
        }
        val status = execute(backend)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(0, backend.troopsDeployed)
        assertTrue(backend.troopCastAttempts in 12..60)
        assertEquals(1, backend.heroAbilities)
        assertEquals(2, backend.spellsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 90000)
    fun theLastTroopCanRecoverOnAnyOfTheTwelveCalibratedPoints() = runBlocking {
        val positions = Json.parseToJsonElement(resource("manifest.json")).jsonObject
            .getValue("screen").jsonObject.getValue("positions").jsonObject
        for (index in 1..12) {
            val point = positions.getValue("deploy$index").jsonObject
            val backend = BattleBackend("fim natural").apply {
                initialTroops = 29
                lastTroopTarget = ScreenPoint(point.getValue("x").jsonPrimitive.int, point.getValue("y").jsonPrimitive.int)
            }
            val status = execute(backend)
            assertEquals("Point $index: ${status.reason}", "village", backend.screen)
            assertEquals(29, backend.troopsDeployed)
            assertEquals(1, backend.heroAbilities)
            assertEquals(2, backend.spellsDeployed)
            assertTrue(backend.troopCastAttempts <= 60)
        }
    }

    @Test(timeout = 20000)
    fun unreadableTroopsAreNotBlindlyCastAndDoNotAbandonTheBattle() = runBlocking {
        val backend = BattleBackend("fim natural").apply { hideTroopCounter = true }
        val status = execute(backend)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(0, backend.troopCastAttempts)
        assertEquals(1, backend.heroAbilities)
        assertEquals(2, backend.spellsDeployed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun aStalledSpellCardDoesNotPreventUsingOtherSpellTypes() = runBlocking {
        val backend = BattleBackend("fim natural").apply {
            spellAmounts = mutableListOf(1, 3)
            rejectedSpell = 0
            naturalEndAfterCaptures = 60
        }
        val status = execute(backend)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(listOf(1, 0), backend.spellAmounts)
        assertEquals(15, backend.spellCastAttempts)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun aTransientSpellConflictRetriesWithoutRepeatingAnArmyDeployment() = runBlocking {
        val backend = BattleBackend("fim natural").apply { spellAmounts = mutableListOf(11) }
        val status = execute(backend, program = withSpellConflicts(2, afterReadings = 2))
        assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(25, backend.troopsDeployed)
        assertEquals(1, backend.heroAbilities)
        assertEquals(11, backend.spellsDeployed)
        assertEquals(0, backend.excessSpellTaps)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 20000)
    fun persistentSpellConflictsLetTheBattleFinishWithoutGuessingTheCounter() = runBlocking {
        val backend = BattleBackend("fim natural").apply { naturalEndAfterCaptures = 40 }
        val status = execute(backend, program = withSpellConflicts(100))
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Limite de 1 batalha"))
        assertEquals(0, backend.spellsDeployed)
        assertEquals(1, backend.openingTaps)
        assertEquals("village", backend.screen)
    }

    private fun withSpellConflicts(count: Int, afterReadings: Int = 0): ScriptProgram {
        val original = sources()
        val source = original.sources.getValue("spells.js")
            .replace("export async function readSpellCards(", "async function readActualSpellCards(") + """
                let conflicts = $count;
                let readings = 0;
                export async function readSpellCards(...args) {
                    if (readings++ >= $afterReadings && conflicts-- > 0) {
                        const error = new Error('Contador de feitiço divergente: 71 e 7');
                        error.code = 'conflicting_spell_counter';
                        throw error;
                    }
                    return readActualSpellCards(...args);
                }
            """.trimIndent()
        return original.copy(sources = original.sources + ("spells.js" to source))
    }

    @Test(timeout = 15000)
    fun zeroBalanceTriggersTheConfiguredMinimumBeforeAnyGesture() = runBlocking {
        val backend = BattleBackend("destruição")
        val status = execute(backend, buildJsonObject { put("goldMinimum", 1) })
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("Saldo mínimo de gold"))
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
    }

    private suspend fun execute(backend: BattleBackend, overrides: JsonObject = JsonObject(emptyMap()), program: ScriptProgram = sources()): ScriptSessionStatus = coroutineScope {
        val manifest = Json.parseToJsonElement(resource("manifest.json")).jsonObject
        val defaults = manifest.getValue("parameters").jsonObject.mapValues { it.value.jsonObject.getValue("default") }
        val options = JsonObject(defaults + mapOf("improveWalls" to JsonPrimitive(false), "stopFarm" to JsonPrimitive("nunca"),
            "goldMinimum" to JsonPrimitive(0), "elixirMinimum" to JsonPrimitive(0), "maxBattles" to JsonPrimitive(1), "finishMode" to JsonPrimitive(backend.finishMode)) + overrides)
        val host = ScreenAutomationHost(backend, { false }, backend.clock::get, options,
            referenceProfile = ScreenProfile.parse(manifest.getValue("screen")))
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        launch { session.run(program, host) }.join()
        session.status.value
    }

    private fun sources(): ScriptProgram {
        val files = listOf("spells.js", "rewards.js", "entry.js", "farm.js", "battle.js", "walls.js", "screen.js", "policy.js", "profile.js", "round.js", "villages.js", "configuration.js", "setup.js")
        return ScriptProgram("entry.js", files.associateWith(::resource) + ("entry.js" to
            "import { parameters } from 'klickr'; import { runFarm } from './farm.js'; await runFarm(await parameters());"))
    }

    private fun resource(path: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/$path"))
        .bufferedReader().use { it.readText() }

    private class BattleBackend(val finishMode: String, heroCount: Int = 1) : ScreenAutomationBackend {
        var ignoredOpeningTaps = 0
        var openingTaps = 0
        var screenAfterIgnoredOpening = "village"
        val clock = AtomicLong()
        val taps = mutableListOf<ScreenPoint>()
        var screen = "village"
        var matchmakingDelayMs = 0L
        var tapsDuringMatchmaking = 0
        private var matchmakingStartedAt = 0L
        var initialTroops = 25
        var wideCounterMissesTroops = false
        var scopedCounterMissesTroops = false
        var collapseElevenTroops = false
        var ignoreTroopCasts = false
        var lastTroopTarget: ScreenPoint? = null
        var hideTroopCounter = false
        var troopCastAttempts = 0
        var troopsDeployed = 0
        var heroAbilities = 0
        var spellsDeployed = 0
        val deploymentOrder = mutableListOf<String>()
        var exitRequests = 0
        var exitConfirmations = 0
        var showStarBonusAfterBattle = false
        var starBonusVisible = false
        var starBonusClaims = 0
        var tapsBehindStarBonus = 0
        var chestStrikes = 0
        var chestConfirmations = 0
        var chestStalled = false
        var clippedSpellHeader = false
        var battlefieldLevelVisible = false
        var spellAmounts = mutableListOf(2)
        var spellImageVisible = true
        var naturalEndAfterCaptures: Int? = null
        var excessSpellTaps = 0
        var ignoreSpellCasts = false
        var rejectedSpell: Int? = null
        var spellCastAttempts = 0
        var hideSpellCountersAfterCasting = false
        private var naturalCaptures = 0
        private var selectedSpell = 0
        private var selected: String? = null
        private val heroPositions = List(heroCount) { ScreenPoint(1500 - (heroCount - it - 1) * 120, 990) }
        private val deployedHeroes = mutableSetOf<Int>()
        private var selectedHero = 0
        private val heroDeployed get() = deployedHeroes.isNotEmpty()
        override suspend fun capture(): ScreenSize {
            clock.addAndGet(if (screen == "matchmaking") 10_000_000_000 else 200_000_000)
            if (screen == "matchmaking" && clock.get() - matchmakingStartedAt >= matchmakingDelayMs * 1_000_000) screen = "opponent"
            if (screen == "battle" && heroDeployed && naturalEndAfterCaptures != null && ++naturalCaptures >= naturalEndAfterCaptures!!) screen = "result"
            return ScreenSize(2340, 1080)
        }
        override suspend fun readText(region: ScreenRegion, model: String, threshold: Int, maxResults: Int): List<ScreenText> =
            if (starBonusVisible && region.left == 650) listOf(
                ScreenText("Bônus de estrela recebido", 99.0, ScreenRegion(810, 200, 1530, 245)),
                ScreenText("Resgatar", 99.0, ScreenRegion(1100, 820, 1260, 870)),
            ) else if (screen == "battle" && region.top == 920 && region.right <= heroPositions.first().x) {
                if (hideTroopCounter || 200 !in region.left until region.right) emptyList()
                else listOf(ScreenText("x${initialTroops-troopsDeployed}", 99.0, ScreenRegion(180, 920, 220, 960)))
            } else if (screen == "battle" && region.top == 920 && !(hideSpellCountersAfterCasting && spellsDeployed > 0)) spellAmounts.mapIndexedNotNull { index, amount ->
                val x = 1800 + index * 120
                if (x !in region.left until region.right) null
                else ScreenText("x$amount", 99.0, ScreenRegion(x - 20, 920, x + 20, if (clippedSpellHeader) 924 else 960))
            }.let { counters ->
                if (battlefieldLevelVisible && spellsDeployed == 0 && 2160 in region.left until region.right)
                    counters + ScreenText("5", 99.0, ScreenRegion(2140, 930, 2180, 955))
                else counters
            } else emptyList()

        override suspend fun recognize(query: RecognitionQuery): ScreenRecognition {
            if (query.kind == RecognitionKind.NUMBER) {
                val spell = spellAmounts.indexOfFirst { it > 0 }
                val value = when {
                    query.reference == "quantity" && screen == "battle" && hideTroopCounter -> null
                    query.reference == "quantity" && screen == "battle" && troopsDeployed < initialTroops &&
                        !(wideCounterMissesTroops && query.region.right > heroPositions.first().x) && !scopedCounterMissesTroops ->
                        if (collapseElevenTroops && initialTroops - troopsDeployed in 8..11) 1.0
                        else (initialTroops - troopsDeployed).toDouble()
                    query.reference == "quantity" && screen == "battle" && spell >= 0 && query.region.right > 1800 -> spellAmounts[spell].toDouble()
                    query.region == ScreenRegion(2140, 815, 2250, 880) && screen == "battle" ->
                        if (finishMode == "saque total" || heroDeployed) 60.0 else 20.0
                    query.region == ScreenRegion(170, 130, 410, 182) || query.region == ScreenRegion(170, 188, 410, 240) ->
                        if (heroDeployed) 0.0 else 10.0
                    query.region.left >= 1910 && query.region.right <= 2180 && query.region.top in 20..30 && query.region.bottom in 85..95 -> 0.0
                    else -> null
                }
                return if (value == null) ScreenRecognition(RecognitionStatus.INVALID_READING, 0.0)
                    else ScreenRecognition(RecognitionStatus.FOUND, 100.0,
                        if (query.reference == "quantity" && (troopsDeployed >= initialTroops ||
                                wideCounterMissesTroops && query.region.right > heroPositions.first().x || scopedCounterMissesTroops))
                            ScreenPoint(1800 + spell * 120, 945) else ScreenPoint(200, 945), value)
            }
            val recognized = when (query.reference) {
                "village", "villageText" -> screen == "village"
                "search", "searchText" -> screen == "search"
                "attack" -> screen == "attack"
                "opponent" -> screen == "opponent"
                "battle" -> screen == "battle" || screen == "opponent"
                "exitConfirm" -> screen == "exitConfirm"
                "result", "resultText" -> screen == "result"
                in heroPositions.indices.map { "hero${it + 1}" } -> screen == "battle" &&
                    query.reference!!.removePrefix("hero").toInt() - 1 !in deployedHeroes
                "troopEleven" -> screen == "battle" && initialTroops - troopsDeployed == 11 &&
                    200 in query.region.left until query.region.right
                "spell" -> screen == "battle" && heroDeployed && spellImageVisible
                "starBonusTitle", "starBonusClaim" -> starBonusVisible
                "chestRoll" -> screen == "chest"
                "chestContinue", "chestRewardNote" -> screen == "reward"
                else -> false
            }
            val position = when (query.reference) {
                "troopEleven" -> ScreenPoint(200, 945)
                in heroPositions.indices.map { "hero${it + 1}" } -> heroPositions[query.reference!!.removePrefix("hero").toInt() - 1]
                "spell" -> ScreenPoint(1000, 1000)
                "exitConfirm" -> ScreenPoint(1380, 700)
                "starBonusClaim" -> ScreenPoint(1180, 845)
                "chestContinue" -> ScreenPoint(1150, 960)
                else -> ScreenPoint((query.region.left + query.region.right) / 2, (query.region.top + query.region.bottom) / 2)
            }
            return ScreenRecognition(if (recognized) RecognitionStatus.FOUND else RecognitionStatus.NOT_FOUND, 100.0,
                if (recognized) position else null)
        }
        override suspend fun tap(position: ScreenPoint, durationMs: Long) {
            taps.add(position)
            if (starBonusVisible) {
                if (position == ScreenPoint(1180, 845)) { starBonusVisible = false; starBonusClaims++; screen = "chest" }
                else tapsBehindStarBonus++
                return
            }
            when (screen) {
                "chest" -> { chestStrikes++; if (chestStrikes == 4 && !chestStalled) screen = "reward" }
                "reward" -> { chestConfirmations++; screen = "village" }
                "village" -> {
                    openingTaps++
                    if (ignoredOpeningTaps > 0) {
                        ignoredOpeningTaps--
                        screen = screenAfterIgnoredOpening
                    } else screen = "search"
                }
                "search" -> screen = "attack"
                "attack" -> {
                    matchmakingStartedAt = clock.get()
                    screen = if (matchmakingDelayMs > 0) "matchmaking" else "opponent"
                }
                "matchmaking" -> tapsDuringMatchmaking++
                "exitConfirm" -> { screen = "result"; exitConfirmations++ }
                "result" -> { screen = "village"; starBonusVisible = showStarBonusAfterBattle }
                "battle" -> when (position) {
                    ScreenPoint(210, 845) -> { screen = "exitConfirm"; exitRequests++ }
                    ScreenPoint(200, 945), ScreenPoint(200, 980) -> selected = "troops"
                    ScreenPoint(1000, 1000) -> selected = "spell"
                    else -> if (position in heroPositions) {
                        selectedHero = heroPositions.indexOf(position)
                        if (selectedHero in deployedHeroes) heroAbilities++ else selected = "hero"
                    } else if (position.y in 930..1065 && position.x >= 1800) {
                        selected = "spell"; selectedSpell = (position.x - 1800) / 120
                    } else when (selected) {
                        "troops" -> {
                            troopCastAttempts++
                            if (!ignoreTroopCasts && troopsDeployed < initialTroops &&
                                (initialTroops - troopsDeployed != 1 || lastTroopTarget == null || position == lastTroopTarget)) {
                                troopsDeployed++; deploymentOrder += "troops"
                            }
                        }
                        "hero" -> { deployedHeroes += selectedHero; deploymentOrder += "heroes" }
                        "spell" -> {
                            spellCastAttempts++
                            if (ignoreSpellCasts || selectedSpell == rejectedSpell) Unit
                            else if (spellAmounts[selectedSpell] > 0) { spellsDeployed++; spellAmounts[selectedSpell]--; deploymentOrder += "spells" }
                            else excessSpellTaps++
                            if (finishMode == "fim natural" && spellAmounts.all { it == 0 }) screen = "result"
                        }
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
