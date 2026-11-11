package com.buzbuz.smartautoclicker.core.script.runtime

/** Immutable source snapshot. Imports can only resolve modules in [sources]. */
data class ScriptProgram(val entry: String, val sources: Map<String, String>)

/** Limits apply to each fresh runtime; busy time excludes awaited host operations. */
data class ScriptRuntimeLimits(
    val memoryBytes: Long = 32L * 1024 * 1024,
    val stackBytes: Long = 256L * 1024,
    val busyTimeoutMs: Long = 5000,
)

/** Host boundary uses JSON strings so scripts never receive Android or JVM objects. */
fun interface ScriptHost {
    /** Executes an API request, for example `host.call("{\"operation\":\"observe\"}")`. */
    suspend fun call(requestJson: String): String
}

/** Expected host failure with a stable code, e.g. `stale_observation` for a discarded frame. */
class ScriptApiException(val code: String, message: String) : IllegalArgumentException(message)

/** Error preserves provider diagnostics without exposing provider types to callers. */
class ScriptExecutionException(
    message: String,
    val sourceFile: String?,
    val sourceLine: Int?,
    val sourceColumn: Int?,
    val scriptStack: String?,
    cause: Throwable,
) : Exception(message, cause)

interface ScriptRuntime {
    /** Compiles all sources without calling host operations, e.g. `runtime.validate(draft)`. */
    suspend fun validate(program: ScriptProgram)

    /** Runs a fixed snapshot; coroutine cancellation interrupts busy JS and pending host calls.
     * Example: `runtime.execute(program, host)` inside a session-owned coroutine.
     */
    suspend fun execute(program: ScriptProgram, host: ScriptHost)
}
