package com.buzbuz.smartautoclicker.core.script.runtime.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Structured event for bounded session logs and the execution overlay. */
data class ScriptDiagnostic(
    val kind: String,
    val stage: String?,
    val message: String,
    val monotonicNanos: Long,
    val operation: String? = null,
    val durationNanos: Long? = null,
    val outcome: String? = null,
    val details: JsonObject? = null,
) {
    /** Encodes an event for a JSON log sink, e.g. `logger.write(event.toJson())`. */
    fun toJson(): String = buildJsonObject {
        put("kind", kind)
        stage?.let { put("stage", it) }
        put("message", message)
        put("monotonicNanos", monotonicNanos)
        operation?.let { put("operation", it) }
        durationNanos?.let { put("durationNanos", it) }
        outcome?.let { put("outcome", it) }
        details?.let { put("details", it) }
    }.toString()
}
