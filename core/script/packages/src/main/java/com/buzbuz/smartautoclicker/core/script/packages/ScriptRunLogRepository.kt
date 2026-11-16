package com.buzbuz.smartautoclicker.core.script.packages

import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/** Private, bounded execution evidence. Call disk methods on an I/O dispatcher.
 * Each run keeps its summary and the two latest event segments; scripts cannot access this directory.
 */
class ScriptRunLogRepository(
    private val root: File,
    private val retainedRuns: Int = 20,
    private val segmentBytes: Long = 524288,
) {
    init {
        require(retainedRuns in 1..100) { "retainedRuns=$retainedRuns; expected 1..100" }
        require(segmentBytes in 16384..1048576) { "segmentBytes=$segmentBytes; expected 16384..1048576" }
    }

    /** Durably marks a run before capture or script effects, e.g. `logs.start(metadata)`. */
    fun start(metadata: JsonObject): Run = synchronized(lock) {
        check(root.isDirectory || root.mkdirs()) { "Não foi possível criar o histórico de execuções" }
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).also { check(it.mkdir()) }
        val summary = JsonObject(metadata + buildJsonObject {
            put("formatVersion", 1); put("runId", id); put("status", "RUNNING")
            put("startedAt", Instant.now().toString()); put("startedAtMillis", System.currentTimeMillis())
        })
        writeSummary(directory, summary)
        prune()
        Run(directory, summary)
    }

    /** Reconciles only another process instance; a missing terminal record alone does not prove a crash.
     * Example: `logs.recoverInterrupted(processToken) { metadata -> androidExitInfo(metadata) }`.
     */
    fun recoverInterrupted(processInstance: String, exitInfo: (JsonObject) -> JsonObject? = { null }) = synchronized(lock) {
        directories().forEach { directory ->
            val summary = readSummary(directory) ?: return@forEach
            if (summary["status"] != JsonPrimitive("RUNNING") || summary["processInstance"] == JsonPrimitive(processInstance)) return@forEach
            val evidence = exitInfo(summary)
            writeSummary(directory, JsonObject(summary + buildJsonObject {
                put("status", "INTERRUPTED"); put("recoveredAt", Instant.now().toString())
                put("reason", "O processo terminou sem registrar o encerramento. Consulte a última etapa e as informações do Android.")
                evidence?.let { put("processExit", it) }
            }))
        }
    }

    /** Exports retained evidence without modifying active runs; e.g. `logs.exportZip(documentStream)`. */
    fun exportZip(output: OutputStream) = synchronized(lock) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("LEIA-ME.txt"))
            zip.write(("Histórico de execuções do Klick’r (formato 1).\n" +
                "summary.json: revisão, início, resultado, motivo e erro. checkpoint.json: último progresso salvo pelo script, sem o campo answers do envelope.\n" +
                "events.previous.jsonl precede events.jsonl; cada linha é um evento JSON independente.\n" +
                "INTERRUPTED indica ausência de encerramento; processExit, quando disponível, informa a causa registrada pelo Android.\n" +
                "Eventos antigos são rotacionados. droppedEvents indica perda por sobrecarga.\n" +
                "Textos do script podem conter nomes de vilas; revise antes de compartilhar.\n").toByteArray())
            zip.closeEntry()
            directories().forEach { directory ->
                for (name in listOf("summary.json", "checkpoint.json", "events.previous.jsonl", "events.jsonl")) {
                    val file = File(directory, name)
                    if (!file.isFile) continue
                    zip.putNextEntry(ZipEntry("${directory.name}/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    inner class Run internal constructor(private val directory: File, private val initial: JsonObject) {
        private var finished = false

        /** Archives confirmed/pending work before the next script effect; e.g. `run.checkpoint(record)`. */
        fun checkpoint(record: JsonObject) = synchronized(lock) {
            check(!finished) { "Execution log is already closed" }
            writeDocument(directory, "checkpoint.json", JsonObject(record - "answers"), 262144)
        }

        /** Appends bounded structured metadata, e.g. `run.append(diagnostic)`. No pixels or source are stored. */
        fun append(event: ScriptDiagnostic) = synchronized(lock) {
            check(!finished) { "Execution log is already closed" }
            val bounded = event.copy(kind = event.kind.take(64), stage = event.stage?.take(256),
                message = event.message.take(4096), operation = event.operation?.take(128), outcome = event.outcome?.take(128),
                details = event.details?.let { if (it.toString().toByteArray().size <= 8192) it else buildJsonObject { put("truncated", true) } })
            val json = Json.parseToJsonElement(bounded.toJson()).jsonObject
            var line = (JsonObject(json + ("timestamp" to JsonPrimitive(Instant.now().toString()))).toString() + "\n").toByteArray()
            if (line.size > segmentBytes) {
                line = (buildJsonObject {
                    put("kind", bounded.kind); put("message", bounded.message.take(1024)); put("truncated", true)
                    put("monotonicNanos", bounded.monotonicNanos); put("timestamp", Instant.now().toString())
                }.toString() + "\n").toByteArray()
            }
            val file = File(directory, "events.jsonl")
            if (file.length() + line.size > segmentBytes) {
                val previous = File(directory, "events.previous.jsonl")
                check(!previous.exists() || previous.delete())
                check(file.renameTo(previous)) { "Não foi possível rotacionar o histórico" }
            }
            FileOutputStream(file, true).use { it.write(line) }
        }

        /** Flushes the final outcome atomically; e.g. `run.finish("STOPPED", reason, failure, droppedEvents)`. */
        fun finish(status: String, reason: String? = null, failure: Throwable? = null, droppedEvents: Long = 0) = synchronized(lock) {
            check(status in setOf("COMPLETED", "STOPPED", "FAILED")) { "status=$status; expected COMPLETED, STOPPED or FAILED" }
            if (finished) return@synchronized
            writeSummary(directory, JsonObject(initial + buildJsonObject {
                put("status", status); put("endedAt", Instant.now().toString()); put("droppedEvents", droppedEvents)
                reason?.let { put("reason", it.take(4096)) }
                failure?.let { put("error", buildJsonObject {
                    put("type", it.javaClass.name); put("message", it.message?.take(4096))
                    put("stackTrace", it.stackTraceToString().take(16384))
                }) }
            }))
            finished = true
        }
    }

    private fun readSummary(directory: File): JsonObject? = runCatching {
        val file = File(directory, "summary.json")
        require(file.length() <= 65536)
        Json.parseToJsonElement(file.readText()).jsonObject
    }.getOrNull()

    private fun writeSummary(directory: File, summary: JsonObject) = writeDocument(directory, "summary.json", summary, 65536)

    private fun writeDocument(directory: File, name: String, value: JsonObject, limit: Int) {
        val bytes = value.toString().toByteArray()
        require(bytes.size <= limit) { "$name.size=${bytes.size}; expected at most $limit bytes" }
        val pending = File(directory, "$name.pending")
        FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        check(pending.renameTo(File(directory, name))) { "Não foi possível salvar $name no histórico da execução" }
    }

    private fun directories(): List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }
        .sortedBy { readSummary(it)?.get("startedAt")?.jsonPrimitive?.content.orEmpty() }

    private fun prune() {
        val directories = directories()
        directories.filter { readSummary(it)?.get("status") != JsonPrimitive("RUNNING") }
            .take((directories.size - retainedRuns).coerceAtLeast(0)).forEach { check(it.deleteRecursively()) }
    }

    private companion object { val lock = Any() }
}
