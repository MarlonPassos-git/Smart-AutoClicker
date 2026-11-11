package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WallExampleIntegrationTest {
    @Test(timeout = 15000)
    fun calibrationGeometryKeepsFreshFrameGuardsAndRevalidatesChangedScreenDimensions() = runBlocking {
        val backend = Backend().apply {
            screen = "confirm"
            onCapture = {
                if (captures == 4) screenSize = ScreenSize(4680, 2160)
                if (captures == 5) screenSize = ScreenSize(1080, 2340)
            }
        }
        val manifest = Json.parseToJsonElement(resource("manifest.json")).jsonObject
        val host = ScreenAutomationHost(backend, { false }, backend.clock::get,
            referenceProfile = ScreenProfile.parse(manifest.getValue("screen")))
        val entry = """
            import { snapshot } from './screen.js';
            import { tap } from 'klickr';
            const first = await snapshot(['batchTitle']);
            const second = await snapshot(['batchTitle']);
            if (second.calibration.positions.batchOk.frame !== second.frame.frame) throw new Error('Position reused an old frame');
            if (!Object.isFrozen(second.calibration) || !Object.isFrozen(second.calibration.positions.batchOk)) throw new Error('Calibration lost its immutable contract');
            let stale = false;
            try { await tap(first.calibration.positions.batchOk); } catch (error) { stale = error.code === 'stale_observation'; }
            if (!stale) throw new Error('Old coordinates authorized a gesture');
            await tap(second.calibration.positions.batchOk);
            const changed = await snapshot(['batchTitle']);
            if (changed.results.batchTitle.status !== 'not_found') throw new Error('Recognition was reused after the screen changed');
            const scaled = await snapshot(['batchTitle']);
            if (scaled.calibration.width !== 4680 || scaled.calibration.positions.batchOk.x !== second.calibration.positions.batchOk.x*2) throw new Error('Dimensions did not invalidate the geometry');
            let rejected = false;
            try { await snapshot(['batchTitle']); } catch (error) { rejected = true; }
            if (!rejected) throw new Error('Incompatible orientation reused the old geometry');
        """.trimIndent()
        val files = listOf("screen.js", "profile.js").associateWith(::resource) + ("entry.js" to entry)
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        session.run(ScriptProgram("entry.js", files), host)
        assertEquals(session.status.value.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, session.status.value.state)
        assertEquals(1, backend.payments)
        assertEquals(1, backend.taps.size)
    }

    @Test(timeout = 15000)
    fun pausingInsidePaymentRecognitionRevalidatesTheConfirmationAndRequiresAFreshGroupBeforePaying() = runBlocking {
        val backend = Backend().apply { screen = "confirm" }
        val manifest = Json.parseToJsonElement(resource("manifest.json")).jsonObject
        val host = ScreenAutomationHost(backend, { false }, backend.clock::get,
            referenceProfile = ScreenProfile.parse(manifest.getValue("screen")))
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        var paused = false
        backend.onRead = { query ->
            if (query.reference == "batchTitle" && !paused) { paused = true; session.pause() }
        }
        val entry = """
            import { confirmPayment } from './walls.js';
            await confirmPayment('elixir',4200000,true);
        """.trimIndent()
        val files = listOf("walls.js", "screen.js", "policy.js", "profile.js").associateWith(::resource) + ("entry.js" to entry)
        val execution = launch { session.run(ScriptProgram("entry.js", files), host) }
        kotlinx.coroutines.withTimeout(5000) {
            session.status.first { it.state == ScriptSessionState.PAUSED }
        }
        assertEquals(0, backend.payments)
        session.resume()
        kotlinx.coroutines.withTimeout(5000) { execution.join() }
        assertEquals(session.status.value.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, session.status.value.state)
        assertEquals(1, backend.payments)
        assertTrue("Pause revalidation cannot authorize an old result group", backend.captures >= 3)
    }

    @Test(timeout = 15000)
    fun insufficientGoldFallsBackToElixirAndRemovesTheUnaffordableExtraWallBeforePaying() = runBlocking {
        val backend = Backend()
        val status = execute(backend)
        assertEquals(status.failure?.stackTraceToString(), "Muros confirmados=2", status.reason)
        assertEquals(1, backend.payments)
        assertEquals("elixir", backend.paidResource)
        assertEquals(8_400_000.0, backend.paidTotal, 0.0)
        assertEquals(2, backend.added)
        assertEquals(1, backend.removed)
        assertEquals("village", backend.screen)
    }

    @Test(timeout = 15000)
    fun aReserveAtTheExactBoundaryAllowsOneWallAndPreventsAddingAnother() = runBlocking {
        val backend = Backend(goldAvailable = true, elixirAvailable = false, goldBalance = 10_200_000.0)
        val status = execute(backend, buildJsonObject { put("goldReserve", 6_000_000) })
        assertEquals(status.failure?.stackTraceToString(), "Muros confirmados=1", status.reason)
        assertEquals(0, backend.added)
        assertEquals("gold", backend.paidResource)
        assertEquals(6_000_000.0, backend.goldBalance!!, 0.0)
    }

    @Test(timeout = 15000)
    fun aReserveAboveTheAvailableBalanceReturnsWithoutOpeningPayment() = runBlocking {
        val backend = Backend(goldAvailable = true, elixirAvailable = false, goldBalance = 10_000_000.0)
        val status = execute(backend, buildJsonObject { put("goldReserve", 6_000_000) })
        assertEquals("Muros confirmados=0", status.reason)
        assertEquals(0, backend.payments)
        assertEquals(0, backend.openedPayments)
        assertEquals(0, backend.added)
    }

    @Test(timeout = 15000)
    fun anUnreadableRequiredBalanceIsNotTreatedAsAnInsufficientZero() = runBlocking {
        val backend = Backend(goldAvailable = true, elixirAvailable = false, goldBalance = null)
        val status = execute(backend, buildJsonObject { put("goldReserve", 1) })
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("preços e reservas"))
        assertEquals(0, backend.openedPayments)
        assertEquals(0, backend.payments)
    }

    @Test(timeout = 15000)
    fun divergentSelectedCostsStopBeforeOpeningAnyUpgradeDialog() = runBlocking {
        val backend = Backend(elixirUnitCost = 4_300_000.0)
        val status = execute(backend)
        assertEquals("Os custos de ouro e elixir do muro não conferem.", status.reason)
        assertEquals(0, backend.openedPayments)
    }

    @Test(timeout = 15000)
    fun aDifferentConfirmationTotalCannotSpendResources() = runBlocking {
        val backend = Backend(confirmationDelta = -1.0)
        val status = execute(backend)
        assertEquals(ScriptSessionState.STOPPED, status.state)
        assertTrue(status.reason!!.contains("confirmação exata"))
        assertEquals(1, backend.openedPayments)
        assertEquals(0, backend.payments)
    }

    @Test(timeout = 15000)
    fun aDifferentConfirmationCurrencyCannotSpendResources() = runBlocking {
        val backend = Backend(wrongCurrency = true)
        val status = execute(backend)
        assertTrue(status.reason!!.contains("confirmação exata"))
        assertEquals(0, backend.payments)
    }

    @Test(timeout = 15000)
    fun anUnconfirmedUpgradeCannotBeCountedOrFollowedByAnotherPayment() = runBlocking {
        val backend = Backend(confirmLevel = false)
        val status = execute(backend)
        assertTrue(status.reason!!.contains("nível 19"))
        assertEquals(1, backend.payments)
        assertEquals("upgraded", backend.screen)
    }

    @Test(timeout = 15000)
    fun noBuilderStopsBeforeZoomOrTap() = runBlocking {
        val backend = Backend(freeBuilder = false)
        val status = execute(backend)
        assertEquals("Nenhum construtor livre para melhorar muros.", status.reason)
        assertEquals(0, backend.zooms)
        assertTrue(backend.taps.isEmpty())
    }

    @Test(timeout = 15000)
    fun noEligibleTargetChecksFourSectorsAndReturnsWithoutTapping() = runBlocking {
        val backend = Backend(eligibleTarget = false)
        val status = execute(backend)
        assertEquals("Muros confirmados=0", status.reason)
        assertEquals(12, backend.zooms)
        assertTrue(backend.taps.isEmpty())
    }

    @Test(timeout = 15000)
    fun levelSixteenWithoutAnEligibleTargetReturnsControlToTheFarmWithoutSpending() = runBlocking {
        val backend = Backend(eligibleTarget = false)
        val status = execute(backend, packageName = "farm-walls-level16-sm-s916b")
        assertEquals("Muros confirmados=0", status.reason)
        assertEquals(12, backend.zooms)
        assertEquals(0, backend.payments)
        assertTrue(backend.taps.isEmpty())
    }

    private suspend fun execute(backend: Backend, overrides: JsonObject = JsonObject(emptyMap()),
        packageName: String = "farm-walls-sm-s916b"): ScriptSessionStatus = coroutineScope {
        val manifest = Json.parseToJsonElement(resource("manifest.json", packageName)).jsonObject
        val defaults = manifest.getValue("parameters").jsonObject.mapValues { it.value.jsonObject.getValue("default") }
        val host = ScreenAutomationHost(backend, { false }, backend.clock::get, JsonObject(defaults + overrides),
            referenceProfile = ScreenProfile.parse(manifest.getValue("screen")))
        val entry = """
            import { parameters, stop } from 'klickr';
            import { improveWalls } from './walls.js';
            const upgraded = await improveWalls(await parameters());
            await stop('Muros confirmados=' + upgraded);
        """.trimIndent()
        val files = listOf("walls.js", "screen.js", "policy.js", "profile.js").associateWith { resource(it, packageName) } + ("entry.js" to entry)
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        launch { session.run(ScriptProgram("entry.js", files), host) }.join()
        session.status.value
    }

    private fun resource(path: String, packageName: String = "farm-walls-sm-s916b") = checkNotNull(javaClass.classLoader!!.getResourceAsStream("$packageName/$path"))
        .bufferedReader().use { it.readText() }

    private class Backend(
        val goldAvailable: Boolean = false,
        val elixirAvailable: Boolean = true,
        var goldBalance: Double? = 10_000_000.0,
        val elixirUnitCost: Double = 4_200_000.0,
        val confirmationDelta: Double = 0.0,
        val wrongCurrency: Boolean = false,
        val confirmLevel: Boolean = true,
        val freeBuilder: Boolean = true,
        val eligibleTarget: Boolean = true,
    ) : ScreenAutomationBackend {
        val clock = AtomicLong()
        val taps = mutableListOf<ScreenPoint>()
        var screen = "village"
        var payments = 0
        var openedPayments = 0
        var paidResource: String? = null
        var paidTotal = 0.0
        var added = 0
        var removed = 0
        var zooms = 0
        var captures = 0
        var screenSize = ScreenSize(2340, 1080)
        var onCapture: () -> Unit = {}
        var onRead: suspend (RecognitionQuery) -> Unit = {}
        private var count = 1
        private var currency = "elixir"
        private var finished = false
        override suspend fun capture(): ScreenSize { captures++; onCapture(); clock.addAndGet(2_000_000_000); return screenSize }
        override suspend fun recognize(query: RecognitionQuery): ScreenRecognition {
            onRead(query)
            if (query.kind == RecognitionKind.NUMBER) {
                val amount = when (query.region) {
                    ScreenRegion(2047, 815, 2076, 855) -> if (freeBuilder) 1.0 else 0.0
                    ScreenRegion(1305, 762, 1437, 790) -> 4_200_000.0
                    ScreenRegion(1515, 762, 1647, 790) -> elixirUnitCost
                    ScreenRegion(1290, 755, 1440, 795) -> count * 4_200_000.0
                    ScreenRegion(1500, 755, 1650, 795) -> count * elixirUnitCost
                    ScreenRegion(1160, 488, 1360, 532) -> count * 4_200_000.0 + confirmationDelta
                    ScreenRegion(1910, 25, 2180, 90) -> goldBalance
                    ScreenRegion(1910, 98, 2180, 163) -> 10_000_000.0
                    else -> null
                }
                return if (amount == null) ScreenRecognition(RecognitionStatus.INVALID_READING, 0.0)
                    else ScreenRecognition(RecognitionStatus.FOUND, 100.0, number = amount)
            }
            val name = query.reference.orEmpty()
            val available = when {
                name.startsWith("gold") -> goldAvailable && !finished && (screen != "batch" || count <= 2)
                name.startsWith("elixir") -> elixirAvailable && !finished && (screen != "batch" || count <= 2)
                else -> false
            }
            val recognized = when {
                name.contains("Insufficient") -> !available
                name.contains("Available") -> available
                name == "village" || name == "villageText" -> screen == "village"
                name == "builderOne" -> freeBuilder && screen == "village"
                name == "wall" -> eligibleTarget && screen == "village"
                name == "wallTitle" -> screen == "selected" || screen == "upgraded"
                name == "selected18" -> screen == "selected" || screen == "batch"
                name == "selected19" -> screen == "upgraded" && confirmLevel
                name == "more" -> screen == "selected"
                name == "batchRemove" || name == "batchAdd" -> screen == "batch"
                name == "batchTitle" || name == "batchOk" -> screen == "confirm"
                name == "${currency}Label" -> screen == "confirm" && !wrongCurrency
                else -> false
            }
            return ScreenRecognition(if (recognized) RecognitionStatus.FOUND else RecognitionStatus.NOT_FOUND, 100.0,
                if (recognized) ScreenPoint((query.region.left + query.region.right) / 2, (query.region.top + query.region.bottom) / 2) else null)
        }
        override suspend fun tap(position: ScreenPoint, durationMs: Long) {
            taps.add(position)
            when (screen) {
                "village" -> screen = "selected"
                "selected" -> when (position) {
                    ScreenPoint(1170, 850) -> { screen = "batch"; count = 1 }
                    ScreenPoint(1800, 1000) -> screen = "village"
                }
                "batch" -> when (position) {
                    ScreenPoint(1170, 850) -> { count++; added++ }
                    ScreenPoint(750, 850) -> { count--; removed++ }
                    ScreenPoint(1370, 850), ScreenPoint(1585, 850) -> {
                        currency = if (position.x == 1370) "gold" else "elixir"
                        openedPayments++; screen = "confirm"
                    }
                    ScreenPoint(600, 120) -> screen = "village"
                }
                "confirm" -> {
                    payments++; paidTotal = count * 4_200_000.0; paidResource = currency
                    if (currency == "gold") goldBalance = goldBalance?.minus(paidTotal)
                    screen = "upgraded"; finished = true
                }
                "upgraded" -> screen = "village"
            }
        }
        override suspend fun tapSequence(steps: List<ScreenTapStep>) { steps.forEach { tap(it.position, it.pressMs) } }
        override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) { zooms++ }
        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) = Unit
        override suspend fun gesture(fingers: List<ScreenGestureFinger>) = Unit
        override suspend fun releasePointers() = Unit
        override suspend fun invalidateObservation() = Unit
    }
}
