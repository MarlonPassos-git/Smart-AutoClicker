package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ScreenAutomationHostTest {
    @Test
    fun detailedTimingsAreOptInAndDoNotReplaceTheStageOrExportParameterAnswers() = runBlocking {
        val clock = AtomicLong()
        val backend = FakeBackend().apply { number = 0.0; onRead = { clock.addAndGet(10_000_000) } }
        val diagnostics = ScriptDiagnostics()
        val host = ScreenAutomationHost(backend, { true }, clock::get,
            startParameters = buildJsonObject { put("privateChoice", "private-answer") },
            onDiagnostic = diagnostics::record, detailedDiagnostics = { diagnostics.isDetailedEnabled })
        host.call("""{"apiVersion":1,"operation":"stage","name":"confirmar","message":"Conferindo valor"}""")
        val frame = Json.parseToJsonElement(host.call(observe)).jsonObject.getValue("frame").jsonPrimitive.content
        host.call(read(frame))
        assertEquals(1, diagnostics.snapshot().size)
        diagnostics.setDetailed(true)
        host.call(read(frame))
        val event = diagnostics.snapshot().last()
        assertEquals("read", event.operation)
        assertEquals("success", event.outcome)
        assertEquals(10_000_000L, event.durationNanos)
        assertEquals("confirmar", event.stage)
        assertEquals("0.0", event.details!!.getValue("number").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"left":0,"top":0,"right":100,"bottom":100}"""), event.details.getValue("region"))
        assertEquals("Conferindo valor", diagnostics.latest.value!!.message)
        host.call("""{"apiVersion":1,"operation":"parameters"}""")
        val exported = diagnostics.jsonLines()
        assertFalse(exported.contains("private-answer"))
        assertFalse(exported.contains("privateChoice"))
        val parameterEvent = Json.parseToJsonElement(exported.lineSequence().last()).jsonObject
        assertEquals("1", parameterEvent.getValue("details").jsonObject.getValue("parameterCount").jsonPrimitive.content)
        diagnostics.setDetailed(false)
        val before = diagnostics.snapshot()
        host.call(read(frame))
        assertEquals(before, diagnostics.snapshot())
        assertEquals(3, backend.reads)
    }

    @Test
    fun detailedGroupsKeepStatusesAndOnlyABoundedNumericSummary() = runBlocking {
        val backend = FakeBackend().apply { number = 42.0 }
        val diagnostics = ScriptDiagnostics(3).apply { setDetailed(true) }
        val host = ScreenAutomationHost(backend, { true }, onDiagnostic = diagnostics::record,
            detailedDiagnostics = { diagnostics.isDetailedEnabled })
        val frame = Json.parseToJsonElement(host.call(observe)).jsonObject.getValue("frame").jsonPrimitive.content
        val query = Json.parseToJsonElement(read(frame)).jsonObject
        host.call(buildJsonObject {
            put("apiVersion", 1); put("operation", "readAll"); put("frame", frame.toLong())
            put("queries", JsonArray(List(14) { query }))
        }.toString())
        val detail = diagnostics.snapshot().last().details!!
        assertEquals("14", detail.getValue("reads").jsonPrimitive.content)
        assertEquals("14", detail.getValue("found").jsonPrimitive.content)
        assertEquals(8, detail.getValue("numbers").jsonArray.size)
        backend.number = null
        host.call(read(frame))
        assertEquals("invalid_reading", diagnostics.snapshot().last().details!!.getValue("status").jsonPrimitive.content)
        assertFalse(diagnostics.snapshot().last().details!!.containsKey("number"))
        host.call(JsonObject(Json.parseToJsonElement(read(frame)).jsonObject + ("reference" to JsonPrimitive("r".repeat(2048)))).toString())
        assertEquals(128, diagnostics.snapshot().last().details!!.getValue("reference").jsonPrimitive.content.length)
        try {
            host.call(buildJsonObject { put("apiVersion", 1); put("operation", "x".repeat(2048)) }.toString())
            fail("Unknown operation must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(128, diagnostics.snapshot().last().operation!!.length)
        assertEquals(128, diagnostics.snapshot().last().message.length)
        repeat(5) { host.call("""{"apiVersion":1,"operation":"now"}""") }
        assertEquals(3, diagnostics.snapshot().size)
        assertEquals(3, diagnostics.jsonLines().lineSequence().count())
    }

    @Test
    fun tracingPreservesFailuresAndRecordsCancellationWithoutContinuingRecognition() = runBlocking {
        val backend = FakeBackend()
        val diagnostics = ScriptDiagnostics().apply { setDetailed(true) }
        val host = ScreenAutomationHost(backend, { true }, onDiagnostic = diagnostics::record,
            detailedDiagnostics = { diagnostics.isDetailedEnabled })
        val frame = Json.parseToJsonElement(host.call(observe)).jsonObject.getValue("frame").jsonPrimitive.content
        val expected = IllegalStateException("recognizer failed")
        backend.onRead = { throw expected }
        try { host.call(read(frame)); fail("Expected the original recognizer failure") }
        catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals("failed", diagnostics.snapshot().last().outcome)
        val entered = CompletableDeferred<Unit>()
        backend.onRead = { entered.complete(Unit); awaitCancellation() }
        val pending = launch { host.call(read(frame)) }
        entered.await()
        pending.cancelAndJoin()
        assertEquals("cancelled", diagnostics.snapshot().last().outcome)
        assertEquals(2, backend.reads)
    }

    @Test
    fun zeroIsAValidNumberAndRegionsAreValidatedBeforeRecognition() = runBlocking {
        val backend = FakeBackend().apply { number = 0.0 }
        val host = ScreenAutomationHost(backend, { true })
        val frame = Json.parseToJsonElement(host.call(observe)).jsonObject.getValue("frame").jsonPrimitive.content
        val result = Json.parseToJsonElement(host.call(read(frame))).jsonObject
        assertEquals("found", result.getValue("status").jsonPrimitive.content)
        assertEquals("0.0", result.getValue("number").jsonPrimitive.content)
        var rejected = false
        try { host.call(read(frame).replace("\"right\":100", "\"right\":300")) }
        catch (failure: IllegalArgumentException) {
            rejected = failure.message?.contains("region=") == true
        }
        assertTrue(rejected)
        assertEquals(1, backend.reads)
    }

    @Test
    fun multipleReadsUseOneCapturedFrameAndInvalidNumbersStayInvalid() = runBlocking {
        val backend = FakeBackend()
        val host = ScreenAutomationHost(backend, { true }, { 123L })
        val observation = Json.parseToJsonElement(host.call(observe)).jsonObject
        val frame = observation.getValue("frame").jsonPrimitive.content
        val first = Json.parseToJsonElement(host.call(read(frame))).jsonObject
        host.call(read(frame))
        assertEquals("123", observation.getValue("monotonicNanos").jsonPrimitive.content)
        assertEquals("invalid_reading", first.getValue("status").jsonPrimitive.content)
        assertFalse(first.containsKey("number"))
        assertEquals(1, backend.captures)
        assertEquals(2, backend.reads)
    }

    @Test
    fun invalidatedFramesCannotTriggerGesturesOrReads() = runBlocking {
        val backend = FakeBackend()
        val host = ScreenAutomationHost(backend, { true })
        val frame = Json.parseToJsonElement(host.call(observe)).jsonObject.getValue("frame").jsonPrimitive.content
        host.invalidateObservations()
        var rejected = false
        try {
            host.call("""{"apiVersion":1,"operation":"tap","frame":$frame,"x":10,"y":20,"durationMs":50}""")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(0, backend.taps)
        host.call(observe)
        rejected = false
        try { host.call(read(frame)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, backend.reads)
    }

    @Test
    fun aGestureInvalidatesItsSourceFrame() = runBlocking {
        val backend = FakeBackend()
        val host = ScreenAutomationHost(backend, { true })
        val frame = Json.parseToJsonElement(host.call(observe)).jsonObject.getValue("frame").jsonPrimitive.content
        host.call("""{"apiVersion":1,"operation":"tap","frame":$frame,"x":10,"y":20,"durationMs":50}""")
        assertEquals(1, backend.taps)
        var rejected = false
        try { host.call(read(frame)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    private val observe = """{"apiVersion":1,"operation":"observe"}"""
    private fun read(frame: String) = """{"apiVersion":1,"operation":"read","frame":$frame,"kind":"number","threshold":80,"region":{"left":0,"top":0,"right":100,"bottom":100}}"""

    private class FakeBackend : ScreenAutomationBackend {
        var captures = 0
        var reads = 0
        var taps = 0
        var number: Double? = null
        var onRead: suspend () -> Unit = {}
        override suspend fun capture(): ScreenSize { captures++; return ScreenSize(200, 400) }
        override suspend fun recognize(query: RecognitionQuery): ScreenRecognition {
            reads++
            onRead()
            return ScreenRecognition(RecognitionStatus.FOUND, 0.95, number = number)
        }
        override suspend fun tap(position: ScreenPoint, durationMs: Long) { taps++ }
        override suspend fun tapSequence(steps: List<ScreenTapStep>) { steps.forEach { tap(it.position, it.pressMs) } }
        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) {}
        override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) {}
        override suspend fun gesture(fingers: List<ScreenGestureFinger>) {}
        override suspend fun releasePointers() {}
        override suspend fun invalidateObservation() {}
    }
}
