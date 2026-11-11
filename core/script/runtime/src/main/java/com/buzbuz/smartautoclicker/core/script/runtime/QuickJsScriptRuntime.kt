package com.buzbuz.smartautoclicker.core.script.runtime

import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.moduleLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A fresh interpreter per operation prevents globals and callbacks leaking into later sessions. */
class QuickJsScriptRuntime(
    private val dispatcher: CoroutineDispatcher,
    private val limits: ScriptRuntimeLimits = ScriptRuntimeLimits(),
) : ScriptRuntime {
    init {
        require(limits.memoryBytes > 0) { "memoryBytes=${limits.memoryBytes}; expected a positive byte count" }
        require(limits.stackBytes > 0) { "stackBytes=${limits.stackBytes}; expected a positive byte count" }
        require(limits.busyTimeoutMs > 0) { "busyTimeoutMs=${limits.busyTimeoutMs}; expected positive milliseconds" }
    }

    override suspend fun validate(program: ScriptProgram) = withInterpreter(program) { runtime, snapshot ->
        snapshot.sources.forEach { (name, code) -> runtime.compile(code, filename = name, asModule = true) }
    }

    override suspend fun execute(program: ScriptProgram, host: ScriptHost) = withInterpreter(program) { runtime, snapshot ->
        runtime.asyncFunction("__klickrCall") { arguments ->
            require(arguments.size == 1 && arguments[0] is String) {
                "API request: expected one JSON string"
            }
            try {
                host.call(arguments[0] as String)
            } catch (expected: ScriptApiException) {
                buildJsonObject {
                    put("status", "error")
                    put("code", expected.code)
                    put("message", expected.message)
                }.toString()
            }
        }
        runtime.evaluate<Unit>(snapshot.sources.getValue(snapshot.entry), filename = snapshot.entry, asModule = true)
    }

    private suspend fun withInterpreter(
        program: ScriptProgram,
        operation: suspend (QuickJs, ScriptProgram) -> Unit,
    ) = withContext(dispatcher) {
        val snapshot = program.copy(sources = program.sources.toMap())
        require("klickr" !in snapshot.sources) { "sources[klickr]: reserved for the versioned automation API" }
        require(snapshot.entry in snapshot.sources) {
            "entry=${snapshot.entry}; expected an existing source file"
        }
        val loader = moduleLoader {
            load { name ->
                val source = if (name == "klickr") ScriptApiV1.source else snapshot.sources[name]
                source?.let(ModuleContent::Source)
            }
        }
        val runtime = QuickJs.create(dispatcher, loader)
        try {
            runtime.memoryLimit = limits.memoryBytes
            runtime.maxStackSize = limits.stackBytes
            // The binding's evaluation deadline includes time awaited in host calls (v1.0.18).
            // Probe its native lock instead: an awaiting runtime responds, busy JS cannot.
            runtime.evaluationTimeoutMillis = 0
            withBusyBudget(runtime) { operation(runtime, snapshot) }
        } catch (error: QuickJsException) {
            throw ScriptExecutionException(
                error.message ?: "JavaScript execution failed", error.fileName, error.lineNumber,
                error.columnNumber, error.stack, error,
            )
        } finally {
            runtime.close()
        }
    }

    @OptIn(com.dokar.quickjs.ExperimentalQuickJsApi::class)
    private suspend fun withBusyBudget(runtime: QuickJs, operation: suspend () -> Unit) = coroutineScope {
        val watchdog = launch(Dispatchers.Default) {
            while (isActive) {
                val probe = async(Dispatchers.IO) { runtime.withNativeContext { } }
                if (withTimeoutOrNull(limits.busyTimeoutMs) { probe.await(); true } == null) {
                    runtime.interruptEvaluation()
                    probe.join()
                    break
                }
                delay(minOf(50, limits.busyTimeoutMs))
            }
        }
        try {
            operation()
        } finally {
            // Native probes must finish before the interpreter is closed.
            watchdog.cancelAndJoin()
        }
    }
}
