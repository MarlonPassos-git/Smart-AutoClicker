package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericExampleIntegrationTest {
    @Test(timeout = 15000)
    fun shippedExampleReobservesAfterAPauseBeforeItsAction() = runBlocking {
        val backend = Backend("ready")
        val result = execute(backend, enabled = true, pauseBeforeAction = true)
        assertEquals(ScriptSessionState.STOPPED, result.state)
        assertEquals(listOf(ScreenPoint(20, 30)), backend.taps)
        assertTrue(result.reason!!.contains("1 ciclo(s) confirmado(s)"))
    }

    @Test(timeout = 15000)
    fun shippedExampleRecoversThenActsAndRequiresAConfirmation() = runBlocking {
        val backend = Backend("closeDialog")
        val result = execute(backend, enabled = true)
        assertEquals(ScriptSessionState.STOPPED, result.state)
        assertEquals(listOf(ScreenPoint(100, 100), ScreenPoint(20, 30)), backend.taps)
        assertTrue(result.reason!!.contains("1 ciclo(s) confirmado(s)"))
    }

    @Test(timeout = 15000)
    fun shippedExampleWaitsForTheDismissalAnimationBeforeConsideringAnotherRecovery() = runBlocking {
        val backend = Backend("closeDialog", dialogFramesAfterDismiss = 3)
        val result = execute(backend, enabled = true)
        assertEquals(ScriptSessionState.STOPPED, result.state)
        assertEquals(listOf(ScreenPoint(100, 100), ScreenPoint(20, 30)), backend.taps)
        assertTrue(result.reason!!.contains("1 ciclo(s) confirmado(s)"))
    }

    @Test(timeout = 15000)
    fun shippedExampleStopsWithoutRepeatingTheTapWhenTheDialogNeverDismisses() = runBlocking {
        val backend = Backend("closeDialog", dialogFramesAfterDismiss = Int.MAX_VALUE)
        val result = execute(backend, enabled = true, monotonicNanos = { backend.captures * 2_000_000_000L })
        assertEquals(ScriptSessionState.STOPPED, result.state)
        assertEquals(listOf(ScreenPoint(100, 100)), backend.taps)
        assertEquals("O diálogo não levou a uma tela prevista em 10 segundos.", result.reason)
    }

    @Test(timeout = 15000)
    fun shippedExampleDefaultsToObservationWithoutDispatchingAGesture() = runBlocking {
        val backend = Backend("ready")
        val result = execute(backend, enabled = false)
        assertEquals(ScriptSessionState.STOPPED, result.state)
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
        assertTrue(result.reason!!.startsWith("Observação concluída"))
    }

    private suspend fun execute(
        backend: Backend,
        enabled: Boolean,
        pauseBeforeAction: Boolean = false,
        monotonicNanos: () -> Long = System::nanoTime,
    ): ScriptSessionStatus = coroutineScope {
        val source = checkNotNull(javaClass.classLoader!!.getResourceAsStream("generic-recovery/entry.js"))
            .bufferedReader().use { it.readText() }
        val profile = ScreenProfile.parse(Json.parseToJsonElement("""{"width":200,"height":400,"regions":{
            "ready":{"left":0,"top":0,"right":200,"bottom":400},
            "completed":{"left":0,"top":0,"right":200,"bottom":400},
            "closeDialog":{"left":0,"top":0,"right":200,"bottom":400}}}"""))
        val host = ScreenAutomationHost(backend, { false }, monotonicNanos = monotonicNanos, startParameters = buildJsonObject {
            put("cycles", 3); put("execute", enabled); put("completion", "parar")
        }, referenceProfile = profile)
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        if (pauseBeforeAction) backend.onCapture = { if (backend.captures == 3) session.pause() }
        val resume = if (pauseBeforeAction) launch {
            withTimeout(5000) { session.status.first { it.state == ScriptSessionState.PAUSED } }
            session.resume()
        } else null
        launch { session.run(ScriptProgram("entry.js", mapOf("entry.js" to source)), host) }.join()
        resume?.join()
        session.status.value
    }

    private class Backend(var screen: String, private var dialogFramesAfterDismiss: Int = 0) : ScreenAutomationBackend {
        val taps = mutableListOf<ScreenPoint>()
        var captures = 0
        var onCapture: () -> Unit = {}
        private var dismissing = false
        override suspend fun capture(): ScreenSize {
            captures++
            onCapture()
            if (dismissing && dialogFramesAfterDismiss-- == 0) {
                screen = "ready"
                dismissing = false
            }
            return ScreenSize(200, 400)
        }
        override suspend fun recognize(query: RecognitionQuery): ScreenRecognition =
            if (query.reference == screen) ScreenRecognition(RecognitionStatus.FOUND, 100.0,
                if (screen == "closeDialog") ScreenPoint(100, 100) else ScreenPoint(20, 30))
            else ScreenRecognition(RecognitionStatus.NOT_FOUND, 0.0)
        override suspend fun tap(position: ScreenPoint, durationMs: Long) {
            taps.add(position)
            if (screen == "closeDialog") {
                if (dialogFramesAfterDismiss == 0) screen = "ready" else dismissing = true
            } else screen = "completed"
        }
        override suspend fun tapSequence(steps: List<ScreenTapStep>) { steps.forEach { tap(it.position, it.pressMs) } }
        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) = Unit
        override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) = Unit
        override suspend fun gesture(fingers: List<ScreenGestureFinger>) = Unit
        override suspend fun releasePointers() = Unit
        override suspend fun invalidateObservation() = Unit
    }
}
