package com.buzbuz.smartautoclicker.core.script.packages

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.serialization.json.*

/** Blocking private checkpoint storage, separate from immutable package revisions.
 * One record per manifest id, one OS file-lock owner; callers dispatch file I/O off the UI thread.
 */
class ScriptExecutionStateRepository(private val root: File) {
    private val configurationRoot = File(root, "configuration")
    init {
        check(root.isDirectory || root.mkdirs()) { "Cannot create execution state directory" }
        check(configurationRoot.isDirectory || configurationRoot.mkdirs()) { "Cannot create configuration directory" }
    }

    private fun file(id: String, configuration: Boolean = false): File {
        require(id.matches(Regex("[a-z0-9][a-z0-9.-]{0,79}"))) { "state.id=$id; expected a manifest id" }
        return File(if (configuration) configurationRoot else root, "$id.json")
    }

    /** Reads without taking ownership; malformed records remain intact. Example: `repository.read(manifest.id)`. */
    fun read(id: String): JsonObject? {
        val value = readObject(file(id)) ?: return null
        validateRecord(value)
        return value
    }

    /** Reads device configuration independently of round progress; e.g. `repository.readConfiguration(id)`. */
    fun readConfiguration(id: String): JsonObject? = readObject(file(id, configuration = true))

    private fun readObject(file: File): JsonObject? {
        if (!file.exists()) return null
        require(file.length() in 1..MAX_BYTES.toLong()) { "Registro local inválido: esperado até $MAX_BYTES bytes. Reconfigure o cadastro ou escolha Nova rodada para o progresso." }
        val value = Json.parseToJsonElement(file.readText()) as? JsonObject
            ?: throw IllegalArgumentException("Registro local inválido: esperado objeto JSON. Reconfigure o cadastro ou escolha Nova rodada para o progresso.")
        return value
    }

    private fun validateRecord(value: JsonObject) {
        val revision = value["revision"] as? JsonPrimitive
        val completed = value["completed"] as? JsonPrimitive
        require(value["formatVersion"] == JsonPrimitive(1) && revision?.isString == true && revision.content.length in 1..256 &&
            value["answers"] is JsonObject && value["state"] is JsonObject && completed?.isString == false && completed.booleanOrNull != null) {
            "Registro de progresso incompatível: esperado formato 1. Escolha Nova rodada."
        }
    }

    /** Acquires exclusive ownership without deleting an earlier round. Example: `repository.acquire(id).use { ... }`. */
    fun acquire(id: String): Owner {
        file(id)
        val lockFile = RandomAccessFile(File(root, "$id.lock"), "rw")
        try {
            val lock = checkNotNull(lockFile.channel.tryLock()) { "A rodada já está em execução" }
            return Owner(id, lockFile, lock)
        } catch (failure: Throwable) { lockFile.close(); throw failure }
    }

    inner class Owner internal constructor(
        private val id: String,
        private val lockFile: RandomAccessFile,
        private val lock: java.nio.channels.FileLock,
    ) : Closeable {
        /** Writes a complete bounded record with fsync before atomic rename; e.g. `owner.write(record)`. */
        fun write(record: JsonObject) {
            validateRecord(record)
            writeObject(record, configuration = false)
        }

        /** Replaces configuration only after setup succeeds, e.g. `owner.writeConfiguration(catalog)`. */
        fun writeConfiguration(value: JsonObject) = writeObject(value, configuration = true)

        private fun writeObject(value: JsonObject, configuration: Boolean) {
            check(lock.isValid) { "Execution state owner is closed" }
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { "state.bytes=${bytes.size}; expected at most $MAX_BYTES" }
            val pending = File(if (configuration) configurationRoot else root, "$id.pending")
            try {
                FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
                check(pending.renameTo(file(id, configuration))) { "Cannot atomically save execution state" }
            } finally { pending.delete() }
        }
        override fun close() { try { lock.release() } finally { lockFile.close() } }
    }

    companion object { private const val MAX_BYTES = 262144 }
}
