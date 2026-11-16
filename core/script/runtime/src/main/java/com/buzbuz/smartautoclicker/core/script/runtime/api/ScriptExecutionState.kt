package com.buzbuz.smartautoclicker.core.script.runtime.api

import kotlinx.serialization.json.JsonObject

/** One bounded private checkpoint owned by the active immutable package execution. */
interface ScriptExecutionState {
    /** Returns the last verified checkpoint, e.g. `state.read()` after restarting the service. */
    suspend fun read(): JsonObject?
    /** Atomically replaces that checkpoint, e.g. `state.write(round, completed = false)`. */
    suspend fun write(value: JsonObject, completed: Boolean)
    /** Reads reusable device configuration, e.g. `state.readConfiguration()` before a new round. */
    suspend fun readConfiguration(): JsonObject? = error("Configuration storage is unavailable")
    /** Atomically saves reusable configuration, e.g. `state.writeConfiguration(catalog)` after setup. */
    suspend fun writeConfiguration(value: JsonObject) { error("Configuration storage is unavailable") }
}
