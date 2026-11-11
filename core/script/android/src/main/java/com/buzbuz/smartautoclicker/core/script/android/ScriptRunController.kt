package com.buzbuz.smartautoclicker.core.script.android

import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostics
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionStatus
import kotlinx.coroutines.flow.StateFlow

/** Service/UI boundary for an Android script session. */
interface ScriptRunController {
    val status: StateFlow<ScriptSessionStatus>
    val diagnostics: ScriptDiagnostics
    /** Requests a cooperative pause, e.g. from the floating pause button. */
    fun pause(): Boolean
    /** Requests a fresh screen check, e.g. from the floating resume button. */
    fun resume(): Boolean
}
