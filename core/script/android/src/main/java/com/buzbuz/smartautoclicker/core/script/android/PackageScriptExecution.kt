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
    val diagnostics: ScriptDiagnostics = ScriptDiagnostics(),
    private val onCheckpoint: (JsonObject) -> Unit = {},
) {
    // Keep interpreter continuations and detector work on the same serial resource thread; the busy watchdog
    // remains independent in QuickJsScriptRuntime so a JavaScript loop can still be interrupted.
    private val runtime = runtime ?: QuickJsScriptRuntime(resourceDispatcher)
    private val mutableStatus = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.PREPARING))
    val status = mutableStatus.asStateFlow()
    @Volatile private var session: ScriptSession? = null
    @Volatile private var execution: Job? = null
    private val started = AtomicBoolean(false)

    /** Runs an immutable revision with validated start answers, e.g. `execution.run(id, answers)`. */
    suspend fun run(storageId: String, suppliedAnswers: JsonObject, newRound: Boolean = false, setup: Boolean = false) = coroutineScope {
        check(started.compareAndSet(false, true)) { "A package execution can only run once" }
        execution = currentCoroutineContext().job
        var stateOwner: com.buzbuz.smartautoclicker.core.script.packages.ScriptExecutionStateRepository.Owner? = null
        var currentCheckpoint: JsonObject? = null
        try {
            val stored = withContext(Dispatchers.IO) {
                repository.listPackages().firstOrNull { it.storageId == storageId }
                    ?: throw IllegalArgumentException("storageId=$storageId; expected an imported script package")
            }
            require(!setup || stored.manifest.setup != null) { "setup: expected a declared setup entry" }
            require(!setup || !newRound) { "setup: cannot replace round progress" }
            val answers = if (setup) JsonObject(emptyMap()) else stored.manifest.answers(suppliedAnswers)
            val checkpoint = if (stored.manifest.document.containsKey("executionState")) withContext(Dispatchers.IO) {
                val states = repository.executionStates()
                stateOwner = states.acquire(stored.manifest.id)
                if (!setup && stored.manifest.parameters.values.any { it.type == "selection" }) {
                    stored.manifest.validateSelections(answers, states.readConfiguration(stored.manifest.id))
                }
                if (newRound) {
                    // The explicit start choice retires the old checkpoint even when the new script never saves one.
                    checkNotNull(stateOwner).write(buildJsonObject {
                        put("formatVersion", 1); put("revision", storageId); put("answers", answers)
                        put("state", JsonObject(emptyMap())); put("completed", true)
                        put("updatedAt", java.time.Instant.now().toString())
                    })
                }
                val previous = if (newRound || setup) null else states.read(stored.manifest.id)?.takeUnless { it.getValue("completed").jsonPrimitive.boolean }
                require(previous == null || previous["revision"]?.jsonPrimitive?.content == storageId) {
                    "O progresso pertence a outra revisão do pacote. Selecione a revisão original ou escolha Nova rodada."
                }
                require(previous == null || previous["answers"] == answers) { "Continuar rodada exige os parâmetros originais; escolha Nova rodada para alterá-los." }
                previous
            } else null
            currentCheckpoint = checkpoint
            if (checkpoint != null) withContext(Dispatchers.IO) { onCheckpoint(checkpoint) }
            val stateApi = if (stateOwner == null) null else object : ScriptExecutionState {
                override suspend fun read(): JsonObject? = currentCheckpoint?.get("state") as? JsonObject
                override suspend fun readConfiguration(): JsonObject? = withContext(Dispatchers.IO) {
                    repository.executionStates().readConfiguration(stored.manifest.id)
                }
                override suspend fun writeConfiguration(value: JsonObject) = withContext(Dispatchers.IO) {
                    check(setup) { "Configuration can only be saved by the setup entry" }
                    checkNotNull(stateOwner).writeConfiguration(value)
                }
                override suspend fun write(value: JsonObject, completed: Boolean) {
                    check(!setup) { "Setup cannot change round progress" }
                    val now = java.time.Instant.now().toString()
                    val record = buildJsonObject {
                        put("formatVersion", 1); put("revision", storageId)
                        put("answers", answers); put("state", value); put("completed", completed)
                        put("createdAt", currentCheckpoint?.get("createdAt") ?: JsonPrimitive(now)); put("updatedAt", now)
                    }
                    withContext(Dispatchers.IO) {
                        checkNotNull(stateOwner).write(record)
                        // Publish the in-memory copy before cancellation can be delivered on the dispatcher return.
                        currentCheckpoint = record
                        onCheckpoint(record)
                    }
                }
            }
            val program = withContext(Dispatchers.IO) {
                repository.program(storageId).let { program ->
                    if (setup) program.copy(entry = checkNotNull(stored.manifest.setup).entry) else program
                }
            }
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
                        detailedDiagnostics = { diagnostics.isDetailedEnabled || diagnostics.isPersistenceEnabled }, executionState = stateApi)
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
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val last = currentCheckpoint
                    val outcome = mutableStatus.value
                    if (last != null && last["completed"] == JsonPrimitive(false) &&
                        outcome.state in setOf(ScriptSessionState.STOPPED, ScriptSessionState.FAILED)) {
                        val interrupted = JsonObject(last + mapOf(
                            "interruptedAt" to JsonPrimitive(java.time.Instant.now().toString()),
                            "interruptionReason" to JsonPrimitive((outcome.reason ?: outcome.failure?.message ?: outcome.state.name).take(4096)),
                        ))
                        checkNotNull(stateOwner).write(interrupted)
                        onCheckpoint(interrupted)
                    }
                } finally { stateOwner?.close() }
            }
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
