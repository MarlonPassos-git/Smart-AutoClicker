package com.buzbuz.smartautoclicker.core.script.runtime.session

import com.buzbuz.smartautoclicker.core.script.runtime.ScriptHost
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptProgram
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptRuntime
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptStopRequested
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

enum class ScriptSessionState {
    PREPARING, RUNNING, PAUSING, PAUSED, REVALIDATING, STOPPING, STOPPED, COMPLETED, FAILED,
}

data class ScriptSessionStatus(val state: ScriptSessionState, val failure: Throwable? = null, val reason: String? = null)

interface SessionAutomationHost : ScriptHost {
    /** Suspends operation deadlines while paused; session/diagnostic clocks keep advancing. */
    suspend fun pauseOperationClock() {}
    /** Restarts operation deadlines after a successful screen revalidation. */
    suspend fun resumeOperationClock() {}
    /** Releases held pointers at a cooperative pause, e.g. before showing a paused overlay. */
    suspend fun releasePointers()
    /** Invalidates previous frame/results, e.g. when entering a paused state. */
    suspend fun invalidateObservations()
    /** Checks the script's declared resume condition against a new observation. */
    suspend fun revalidate(): Boolean
}

/** A single-use execution preserving the JS stack only at supported host API checkpoints. */
class ScriptSession(
    private val runtime: ScriptRuntime,
    private val coordinator: AutomationSessionCoordinator,
) {
    private val control = Any()
    private val mutableStatus = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.PREPARING))
    val status: StateFlow<ScriptSessionStatus> = mutableStatus.asStateFlow()
    private var execution: Job? = null
    private var started = false
    @Volatile private var stopReason: String? = null

    /** Runs a fixed source snapshot. Example: `scope.launch { session.run(program, host) }`. */
    suspend fun run(program: ScriptProgram, host: SessionAutomationHost) {
        val snapshot = program.copy(sources = program.sources.toMap())
        val job = currentCoroutineContext().job
        synchronized(control) {
            check(!started) { "A ScriptSession can only run once" }
            started = true
            execution = job
        }
        try {
            coordinator.withSession { access ->
                try {
                    runtime.validate(snapshot)
                    currentCoroutineContext().ensureActive()
                    transition(ScriptSessionState.PREPARING, ScriptSessionState.RUNNING)
                    runtime.execute(snapshot) { request ->
                        access.perform {
                            checkpoint(host)
                            currentCoroutineContext().ensureActive()
                            try {
                                host.call(request)
                            } catch (requested: ScriptStopRequested) {
                                stop(requested.reason)
                                throw CancellationException(requested.reason, requested)
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        access.perform {
                            host.releasePointers()
                            host.invalidateObservations()
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(control) {
                val stopped = mutableStatus.value.state == ScriptSessionState.STOPPING
                mutableStatus.value = ScriptSessionStatus(
                    if (stopped) ScriptSessionState.STOPPED else ScriptSessionState.COMPLETED,
                    reason = if (stopped) stopReason else null,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableStatus.value = ScriptSessionStatus(ScriptSessionState.STOPPED, reason = stopReason)
            throw cancelled
        } catch (failure: Exception) {
            mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
        } finally {
            synchronized(control) { execution = null }
        }
    }

    /** Requests a pause at the next API checkpoint; returns false outside RUNNING. */
    fun pause(): Boolean = transition(ScriptSessionState.RUNNING, ScriptSessionState.PAUSING)

    /** Requests a fresh screen check before continuing; returns false outside PAUSED. */
    fun resume(): Boolean = transition(ScriptSessionState.PAUSED, ScriptSessionState.REVALIDATING)

    /** Cancels waits, host calls and busy JS. Example: `session.stop()` from an overlay. */
    fun stop(reason: String = "Interrompido pelo usuário"): Boolean = synchronized(control) {
        val job = execution ?: return false
        if (mutableStatus.value.state in terminalStates) return false
        stopReason = reason
        mutableStatus.value = ScriptSessionStatus(ScriptSessionState.STOPPING)
        job.cancel()
        true
    }

    private suspend fun checkpoint(host: SessionAutomationHost) {
        while (true) {
            currentCoroutineContext().ensureActive()
            when (status.value.state) {
                ScriptSessionState.RUNNING -> return
                ScriptSessionState.PAUSING -> {
                    host.pauseOperationClock()
                    host.releasePointers()
                    host.invalidateObservations()
                    transition(ScriptSessionState.PAUSING, ScriptSessionState.PAUSED)
                }
                ScriptSessionState.PAUSED -> status.first { it.state != ScriptSessionState.PAUSED }
                ScriptSessionState.REVALIDATING -> {
                    check(host.revalidate()) { "A tela não foi confirmada ao retomar. Inicie novamente na tela esperada." }
                    host.resumeOperationClock()
                    transition(ScriptSessionState.REVALIDATING, ScriptSessionState.RUNNING)
                }
                else -> throw CancellationException("Script session is stopping")
            }
        }
    }

    private fun transition(from: ScriptSessionState, to: ScriptSessionState): Boolean = synchronized(control) {
        if (mutableStatus.value.state != from) return false
        mutableStatus.value = ScriptSessionStatus(to)
        true
    }

    private companion object {
        val terminalStates = setOf(ScriptSessionState.STOPPED, ScriptSessionState.COMPLETED, ScriptSessionState.FAILED)
    }
}
