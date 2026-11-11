package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.QuickJsScriptRuntime
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptRuntime
import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Executes one package against a caller-owned projection and accessibility service.
 * The caller must acquire exclusive ownership before invoking [run] and close projection afterwards.
 */
class PackageScriptExecution(
    private val repository: ScriptPackageRepository,
    private val resourceDispatcher: CoroutineDispatcher,
    private val actions: AndroidActionExecutor,
    private val createDetector: () -> ImageDetector,
    private val captureBitmap: suspend () -> Bitmap?,
    private val loadOcrModels: suspend (ImageDetector, Set<String>) -> Unit,
    private val processingTag: String,
    runtime: ScriptRuntime? = null,
) {
    // Keep interpreter continuations and detector work on the same serial resource thread; the busy watchdog
    // remains independent in QuickJsScriptRuntime so a JavaScript loop can still be interrupted.
    private val runtime = runtime ?: QuickJsScriptRuntime(resourceDispatcher)
    private val mutableStatus = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.PREPARING))
    val status = mutableStatus.asStateFlow()
    val diagnostics = ScriptDiagnostics()
    @Volatile private var session: ScriptSession? = null
    @Volatile private var execution: Job? = null
    private val started = AtomicBoolean(false)

    /** Runs an immutable revision with validated start answers, e.g. `execution.run(id, answers)`. */
    suspend fun run(storageId: String, suppliedAnswers: JsonObject) = coroutineScope {
        check(started.compareAndSet(false, true)) { "A package execution can only run once" }
        execution = currentCoroutineContext().job
        try {
            val stored = withContext(Dispatchers.IO) {
                repository.listPackages().firstOrNull { it.storageId == storageId }
                    ?: throw IllegalArgumentException("storageId=$storageId; expected an imported script package")
            }
            val answers = stored.manifest.answers(suppliedAnswers)
            val program = withContext(Dispatchers.IO) { repository.program(storageId) }
            withContext(resourceDispatcher) {
                val resources = PackageScreenResources(repository, stored)
                val detector = createDetector()
                var backend: AndroidScreenAutomationBackend? = null
                try {
                    detector.init()
                    val loadedModels = mutableSetOf<String>()
                    suspend fun ensureModels(required: Set<String>) {
                        if (loadedModels.containsAll(required)) return
                        val combined = loadedModels + required
                        loadOcrModels(detector, combined)
                        loadedModels.addAll(required)
                    }
                    // Decode declared templates before the script's first reaction-critical operation.
                    stored.manifest.resources.keys.forEach { resources.resolve(it) }
                    backend = AndroidScreenAutomationBackend(detector, actions, captureBitmap, resources::resolve, processingTag, ::ensureModels)
                    val host = ScreenAutomationHost(backend, resumeCheck = { false }, startParameters = answers,
                        onDiagnostic = diagnostics::record,
                        initialResumeQuery = stored.manifest.document["resumeCheck"] as? JsonObject,
                        referenceProfile = stored.manifest.document["screen"]?.let(ScreenProfile::parse),
                        detailedDiagnostics = { diagnostics.isDetailedEnabled })
                    val current = ScriptSession(runtime, AutomationSessionCoordinator(resourceDispatcher) {})
                    session = current
                    val collector = launch { current.status.collect { mutableStatus.value = it } }
                    try {
                        current.run(program, host)
                    } finally {
                        withContext(NonCancellable) {
                            collector.cancelAndJoin()
                            mutableStatus.value = current.status.value
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        try { actions.releaseHeldPointer() }
                        finally {
                            try { backend?.invalidateObservation() }
                            finally {
                                try { detector.close() } finally { resources.close() }
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (mutableStatus.value.state != ScriptSessionState.STOPPED) {
                mutableStatus.value = ScriptSessionStatus(ScriptSessionState.STOPPED, reason = cancelled.message)
            }
            throw cancelled
        } catch (failure: Exception) {
            mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
        } finally {
            session = null
            execution = null
        }
    }

    /** Requests a cooperative pause. Example: `execution.pause()` from the overlay. */
    fun pause(): Boolean = session?.pause() ?: false

    /** Requests screen revalidation; scripts without a declared check must fail rather than click blindly. */
    fun resume(): Boolean = session?.resume() ?: false

    /** Cancels preparation or execution, e.g. `execution.stop("Captura encerrada")`. */
    fun stop(reason: String = "Interrompido pelo usuário") {
        session?.stop(reason)
        execution?.cancel(CancellationException(reason))
    }
}
