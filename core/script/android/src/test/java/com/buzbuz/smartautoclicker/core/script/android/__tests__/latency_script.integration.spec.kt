package com.buzbuz.smartautoclicker.core.script.android

import com.buzbuz.smartautoclicker.core.script.runtime.QuickJsScriptRuntime
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptProgram
import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LatencyScriptIntegrationTest {
    @Test(timeout = 15000)
    fun measurementConfirmsEveryColorTransitionAndExportsTwentySamplesWithOrWithoutTracing() = runBlocking {
        for (detailed in listOf(false, true)) {
            val fixture = Fixture(true, detailed)
            fixture.run()
            assertEquals(ScriptSessionState.COMPLETED, fixture.status!!.state)
            assertEquals(23, fixture.taps)
            val report = Json.parseToJsonElement(fixture.diagnostics.snapshot().last { it.kind == "log" }.message).jsonObject
            assertEquals("latency_target_samples", report.getValue("kind").jsonPrimitive.content)
            assertEquals(7, report.getValue("round").jsonPrimitive.int)
            val rows = report.getValue("rows").jsonArray
            assertEquals(20, rows.size)
            assertEquals((3..22).toList(), rows.map { it.jsonArray.first().jsonPrimitive.int })
            for (row in rows) {
                val times = row.jsonArray.drop(1).take(5).map { it.jsonPrimitive.double }
                assertTrue(times.zipWithNext().all { (before, after) -> before <= after })
            }
        }
    }

    @Test(timeout = 15000)
    fun aDifferentTargetStopsTheMeasurementBeforeAnyTouch() = runBlocking {
        val fixture = Fixture(false, false)
        fixture.run()
        assertEquals(ScriptSessionState.STOPPED, fixture.status!!.state)
        assertTrue(fixture.status!!.reason.orEmpty().contains("alvo controlado não foi confirmado"))
        assertEquals(0, fixture.taps)
    }

    private class Fixture(signatureMatches: Boolean, detailed: Boolean) {
        var taps = 0
        val diagnostics = ScriptDiagnostics().apply { setDetailed(detailed) }
        var status: ScriptSessionStatus? = null
        private val backend = mockk<ScreenAutomationBackend>(relaxed = true).also { backend ->
            coEvery { backend.capture() } returns ScreenSize(1080, 2400)
            coEvery { backend.recognize(any()) } coAnswers {
                val query = firstArg<RecognitionQuery>()
                assertEquals(0, query.threshold)
                val reference = query.reference
                val found = if (reference == "signature") signatureMatches
                    else reference == if (taps % 2 == 0) "green" else "blue"
                ScreenRecognition(if (found) RecognitionStatus.FOUND else RecognitionStatus.NOT_FOUND, if (found) 100.0 else 0.0)
            }
            coEvery { backend.tap(any(), any()) } coAnswers {
                assertEquals(ScreenPoint(540, 1920), firstArg<ScreenPoint>())
                assertEquals(1L, secondArg<Long>())
                taps++
            }
        }

        suspend fun run() {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val source = checkNotNull(javaClass.classLoader.getResourceAsStream("latency-target/entry.js"))
                    .bufferedReader().use { it.readText() }
                val host = ScreenAutomationHost(backend, { false },
                    startParameters = buildJsonObject { put("round", 7); put("startDelayMs", 0) },
                    onDiagnostic = diagnostics::record, detailedDiagnostics = { diagnostics.isDetailedEnabled })
                val session = ScriptSession(QuickJsScriptRuntime(dispatcher), AutomationSessionCoordinator(dispatcher) {})
                coroutineScope {
                    launch { session.run(ScriptProgram("entry.js", mapOf("entry.js" to source)), host) }.join()
                }
                status = session.status.value
            }
        }
    }
}
