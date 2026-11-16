package com.buzbuz.smartautoclicker.core.script.packages

import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLogsIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun metadata(process: String = "process-1", revision: String = "reviewed-revision") = buildJsonObject {
        put("processInstance", process); put("revision", revision); put("packageId", "farm")
    }

    @Test fun cancelAndFailureSurviveReopeningWithTheirLastOperationsAndCanBeExported() {
        val root = temporary.newFolder()
        val logs = ScriptRunLogRepository(root)
        val cancelled = logs.start(metadata())
        cancelled.append(ScriptDiagnostic("stage", "Ataque", "Entrada na batalha 2", 123))
        cancelled.finish("STOPPED", "Captura de tela encerrada")
        val failed = logs.start(metadata(revision = "next-revision"))
        failed.checkpoint(buildJsonObject {
            put("revision", "next-revision"); put("answers", buildJsonObject { put("private", "form-response") })
            put("state", buildJsonObject { put("confirmed", 2); put("pending", "attack") })
        })
        failed.append(ScriptDiagnostic("operation", "Captura", "observe", 456, operation = "observe", outcome = "failed"))
        failed.finish("FAILED", failure = IllegalStateException("Imagem de captura indisponível"), droppedEvents = 2)
        val exported = export(ScriptRunLogRepository(root))
        val summaries = exported.filterKeys { it.endsWith("summary.json") }.values.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, summaries.size)
        assertEquals("Captura de tela encerrada", summaries.first { it["status"] == JsonPrimitive("STOPPED") }["reason"]?.jsonPrimitive?.content)
        val failure = summaries.first { it["status"] == JsonPrimitive("FAILED") }
        assertEquals("next-revision", failure["revision"]?.jsonPrimitive?.content)
        assertEquals(2, failure["droppedEvents"]?.jsonPrimitive?.int)
        assertTrue(failure["error"].toString().contains("Imagem de captura indisponível"))
        assertTrue(exported.values.any { it.contains("Entrada na batalha 2") })
        assertTrue(exported.values.any { it.contains("\"operation\":\"observe\"") && it.contains("\"outcome\":\"failed\"") })
        val checkpoint = exported.filterKeys { it.endsWith("checkpoint.json") }.values.single()
        assertTrue(checkpoint.contains("attack"))
        assertFalse(checkpoint.contains("form-response"))
    }

    @Test fun recoveryPreservesLiveRunsAndDoesNotInventACrashWhenTheProcessVanished() {
        val root = temporary.newFolder()
        val logs = ScriptRunLogRepository(root)
        logs.start(metadata())
        logs.start(metadata("current-process", "live"))
        logs.recoverInterrupted("current-process")
        val summaries = root.listFiles()!!.map { Json.parseToJsonElement(it.resolve("summary.json").readText()).jsonObject }
        val interrupted = summaries.first { it["revision"] == JsonPrimitive("reviewed-revision") }
        assertEquals(JsonPrimitive("INTERRUPTED"), interrupted["status"])
        assertNull(interrupted["processExit"])
        assertEquals(JsonPrimitive("RUNNING"), summaries.first { it["revision"] == JsonPrimitive("live") }["status"])
        // Recovery is idempotent and can attach concrete Android evidence to a different abandoned run.
        logs.recoverInterrupted("new-process") { buildJsonObject { put("crashConfirmed", true); put("reasonCode", 5) } }
        val recovered = root.listFiles()!!.map { Json.parseToJsonElement(it.resolve("summary.json").readText()).jsonObject }
        assertEquals(interrupted, recovered.first { it["revision"] == JsonPrimitive("reviewed-revision") })
        assertEquals(JsonPrimitive(true), recovered.first { it["revision"] == JsonPrimitive("live") }["processExit"]?.jsonObject?.get("crashConfirmed"))
    }

    @Test fun eventRotationKeepsRecentEvidenceAndRunRetentionBoundsDiskUsage() {
        val root = temporary.newFolder()
        val logs = ScriptRunLogRepository(root, retainedRuns = 3, segmentBytes = 16384)
        repeat(6) { index ->
            val run = logs.start(metadata(revision = "revision-$index"))
            repeat(80) { event -> run.append(ScriptDiagnostic("log", null, "$event:${"x".repeat(2000)}", event.toLong())) }
            run.finish("COMPLETED")
        }
        assertEquals(3, root.listFiles()!!.size)
        root.listFiles()!!.forEach { directory ->
            assertTrue(directory.resolve("events.jsonl").length() <= 16384)
            assertTrue(directory.resolve("events.previous.jsonl").length() <= 16384)
            val last = directory.resolve("events.jsonl").readLines().last()
            assertTrue(Json.parseToJsonElement(last).jsonObject["message"]!!.jsonPrimitive.content.startsWith("79:"))
        }
        assertEquals(3, export(logs).keys.count { it.endsWith("summary.json") })
    }

    @Test fun oversizedUnicodeEventsRemainValidJsonWithinTheSegmentLimit() {
        val root = temporary.newFolder()
        val run = ScriptRunLogRepository(root, segmentBytes = 16384).start(metadata())
        run.append(ScriptDiagnostic("log", "中".repeat(256), "中".repeat(4096), 1,
            details = buildJsonObject { put("reference", "x".repeat(7000)) }))
        run.finish("COMPLETED")
        val file = root.listFiles()!!.single().resolve("events.jsonl")
        assertTrue(file.length() <= 16384)
        assertEquals(JsonPrimitive(true), Json.parseToJsonElement(file.readText()).jsonObject["truncated"])
    }

    private fun export(logs: ScriptRunLogRepository): Map<String, String> {
        val output = ByteArrayOutputStream()
        logs.exportZip(output)
        return buildMap {
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
    }
}
