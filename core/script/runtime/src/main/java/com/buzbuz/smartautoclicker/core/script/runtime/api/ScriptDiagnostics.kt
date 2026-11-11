package com.buzbuz.smartautoclicker.core.script.runtime.api

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded history with a separate latest stage/message for the execution controls. */
class ScriptDiagnostics(private val capacity: Int = 200) {
    private val events = ArrayDeque<ScriptDiagnostic>()
    private val mutableLatest = MutableStateFlow<ScriptDiagnostic?>(null)
    val latest = mutableLatest.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    @Volatile var isDetailedEnabled: Boolean = false
        private set

    init { require(capacity in 1..1000) { "capacity=$capacity; expected 1..1000 events" } }

    /** Enables operation timings only when requested, e.g. `diagnostics.setDetailed(viewerVisible)`. */
    fun setDetailed(enabled: Boolean) { isDetailedEnabled = enabled }

    /** Records one structured event, e.g. `host = ScreenAutomationHost(..., onDiagnostic = diagnostics::record)`. */
    @Synchronized
    fun record(event: ScriptDiagnostic) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(event)
        // Operation traces belong in the detailed viewer; they must not replace the current stage/wait message.
        if (event.kind != "operation") mutableLatest.value = event
        mutableRevision.value += 1
    }

    /** Returns a stable snapshot for the diagnostic viewer. */
    @Synchronized
    fun snapshot(): List<ScriptDiagnostic> = events.toList()

    /** Exports retained events as JSON Lines, e.g. `report.writeText(diagnostics.jsonLines())`. */
    @Synchronized
    fun jsonLines(): String = events.joinToString("\n") { it.toJson() }
}
