package com.buzbuz.smartautoclicker.localservice

import android.content.Context
import android.content.Intent
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.OCRModelsRepository
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModelState
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptRuntime
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.script.android.PackageScriptExecution
import com.buzbuz.smartautoclicker.core.script.android.ScriptRunController
import com.buzbuz.smartautoclicker.core.script.android.ScriptRecoveryChoice
import com.buzbuz.smartautoclicker.core.script.android.ScriptRunLogs
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.packages.ScriptRunLogRepository
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostics
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Projection lifetime owned by LocalService's shared resource lease. Failed runs can retry over the same live projection. */
class ScriptServiceDriver(
    private val context: Context,
    private val recorder: DisplayRecorder,
    private val display: DisplayConfigManager,
    private val actions: AndroidActionExecutor,
    private val models: OCRModelsRepository,
    private val processingTag: String,
    private val createDetector: () -> ImageDetector = { checkNotNull(NativeDetector.newInstance()) { "Biblioteca de detecção indisponível" } },
    private val runtime: ScriptRuntime? = null,
) : ScriptRunController {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val repository = ScriptPackageRepository(File(context.filesDir, "scripts/packages"))
    @Volatile private var logEvents = Channel<ScriptDiagnostic>(512)
    private val droppedEvents = AtomicLong()
    @Volatile private var journal: ScriptRunLogRepository.Run? = null
    override val diagnostics = ScriptDiagnostics(onRecord = { event ->
        if (logEvents.trySend(event).isFailure) droppedEvents.incrementAndGet()
    }).apply { isPersistenceEnabled = true }
    private fun createExecution() = PackageScriptExecution(
        repository, dispatcher, actions,
        createDetector,
        recorder::acquireLatestBitmap,
        { detector, required ->
            val detection = (models.getDetectionModel()?.state as? OCRModelState.Installed)?.path
                ?: error("Modelo de detecção de texto ausente")
            val recognition = required.associateWith { name ->
                val alphabet = OCRAlphabet.entries.find { it.name.equals(name, ignoreCase = true) }
                    ?: error("Modelo OCR desconhecido: $name")
                models.getRecognitionModelPath(alphabet) ?: error("Modelo OCR ausente: $name")
            }
            check(detector.loadTextDetectionModels(detection, recognition)) { "Não foi possível carregar os modelos OCR" }
        }, processingTag, runtime, diagnostics, onCheckpoint = { recoveryCheckpoint = it; journal?.checkpoint(it) },
    )
    @Volatile private var recoveryCheckpoint: JsonObject? = null
    @Volatile private var execution = createExecution()
    private data class RetryRequest(val choice: String? = null, val checkpoint: JsonObject? = null)
    private val retryRequests = Channel<RetryRequest>(1)
    private val retryQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.PREPARING))
    override val status = mutableStatus.asStateFlow()
    private var job: Job? = null
    @Volatile private var closing = false
    @Volatile private var preparingCapture = true
    private var projectionFailure: Exception? = null
    private var captureCleanupAttempted = false
    private val orientationListener: (Context) -> Unit = {
        if (!preparingCapture) stop("Orientação da tela alterada; inicie novamente na orientação calibrada")
    }

    /** Starts after foreground-service permission and exclusive ownership have been established. */
    fun start(scope: CoroutineScope, resultCode: Int, data: Intent, storageId: String, answersJson: String, newRound: Boolean = false, setup: Boolean = false) {
        check(job == null) { "Script service driver can only start once" }
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val stored = withContext(Dispatchers.IO) { repository.listPackages().first { it.storageId == storageId } }
                val answers = Json.parseToJsonElement(answersJson) as? JsonObject
                    ?: error("Parâmetros de início devem ser um objeto JSON")
                display.addOrientationListener(orientationListener)
                var replaceRound = newRound
                while (isActive) {
                    runAttempt(stored, answers, replaceRound, setup) {
                        if (preparingCapture) prepareCapture(resultCode, data, stored.manifest.document["screen"] as? JsonObject)
                    }
                    if (closing || preparingCapture || mutableStatus.value.state !in setOf(ScriptSessionState.FAILED, ScriptSessionState.STOPPED)) break
                    // Keep the same live projection/virtual display. Reusing its consent
                    // Intent after stopProjection() is forbidden on Android 14+.
                    val retry = retryRequests.receive()
                    mutableStatus.value = ScriptSessionStatus(ScriptSessionState.PREPARING)
                    retryQueued.set(false)
                    if (retry.choice != null) withContext(Dispatchers.IO) {
                        val states = repository.executionStates()
                        states.acquire(stored.manifest.id).use { owner ->
                            val expected = checkNotNull(retry.checkpoint)
                            val latest = checkNotNull(states.read(stored.manifest.id))
                            // Finalization may only add interruption timestamps after the
                            // user taps. Bind the choice to progress/revision/answers.
                            check(listOf("state", "revision", "answers", "completed").all { latest[it] == expected[it] }) {
                                "O progresso mudou; confira novamente antes de resolver a pendência."
                            }
                            val checkpoint = latest.getValue("state").jsonObject
                            val recovery = checkpoint.getValue("recovery").jsonObject
                            val answered = JsonObject(recovery + ("answer" to JsonPrimitive(retry.choice)))
                            owner.write(JsonObject(latest + ("state" to JsonObject(checkpoint + ("recovery" to answered)))))
                        }
                    }
                    execution = createExecution()
                    replaceRound = false
                }
            } catch (cancelled: CancellationException) {
                if (mutableStatus.value.state !in setOf(ScriptSessionState.COMPLETED, ScriptSessionState.FAILED, ScriptSessionState.STOPPED)) {
                    mutableStatus.value = ScriptSessionStatus(ScriptSessionState.STOPPED, reason = cancelled.message)
                }
            } catch (failure: Exception) {
                mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
            } finally {
                withContext(NonCancellable) {
                    try { closeCapture() } finally { dispatcher.close() }
                }
            }
        }
        job?.start()
    }

    private suspend fun closeCapture() {
        if (captureCleanupAttempted) return
        captureCleanupAttempted = true
        closing = true
        display.removeOrientationListener(orientationListener)
        try { recorder.stopProjection() }
        catch (failure: Exception) {
            projectionFailure = failure
            mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
        }
    }

    private suspend fun prepareCapture(resultCode: Int, data: Intent, expected: JsonObject?) {
        recorder.startProjection(resultCode, data) { if (!closing) stop("Captura de tela encerrada") }
        delay(500) // Let the configuration activity leave before choosing capture dimensions.
        if (expected != null) {
            diagnostics.record(ScriptDiagnostic("stage", "Preparando captura", "Aguardando o aplicativo na orientação calibrada.", System.nanoTime()))
            val width = expected.getValue("width").jsonPrimitive.int
            val height = expected.getValue("height").jsonPrimitive.int
            try { withTimeout(30000) {
                do {
                    val size = display.displayConfig.sizePx
                    val compatible = size.x > 0 && size.y > 0 && kotlin.math.abs(size.x.toDouble() * height / (size.y.toDouble() * width) - 1) <= 0.01
                    if (compatible) {
                        delay(250)
                        if (display.displayConfig.sizePx == size) break
                    } else delay(50)
                } while (true)
            } } catch (timeout: TimeoutCancellationException) {
                throw IllegalStateException("O aplicativo não ficou na orientação calibrada em 30 segundos. Abra o aplicativo e inicie novamente.", timeout)
            }
        }
        recorder.startScreenRecord(display.displayConfig.sizePx)
        preparingCapture = false
    }

    private suspend fun runAttempt(
        stored: com.buzbuz.smartautoclicker.core.script.packages.StoredScriptPackage,
        answers: JsonObject,
        newRound: Boolean,
        setup: Boolean,
        prepare: suspend () -> Unit,
    ) = coroutineScope {
        val collector = launch { execution.status.collect { mutableStatus.value = it } }
        var logWriter: Job? = null
        var logFailure: Exception? = null
        logEvents = Channel(512)
        droppedEvents.set(0)
        try {
            journal = withContext(Dispatchers.IO) {
                ScriptRunLogs.open(context).start(JsonObject(ScriptRunLogs.environment(context) + buildJsonObject {
                    put("revision", stored.storageId); put("packageId", stored.manifest.id); put("scriptVersion", stored.manifest.scriptVersion)
                    put("entry", if (setup) stored.manifest.setup?.entry else stored.manifest.entry)
                    put("setup", setup); put("newRound", newRound); put("targetPackage", stored.manifest.targetPackage)
                }))
            }
            val runLog = checkNotNull(journal)
            val events = logEvents
            logWriter = launch(NonCancellable + Dispatchers.IO) {
                try { for (event in events) runLog.append(event) }
                catch (failure: Exception) {
                    logFailure = failure
                    stop("Não foi possível salvar o histórico da execução: ${failure.message}")
                }
            }
            diagnostics.record(ScriptDiagnostic("lifecycle", null, "Preparando execução e captura", System.nanoTime()))
            prepare()
            execution.run(stored.storageId, answers, newRound, setup)
        } catch (cancelled: CancellationException) {
            mutableStatus.value = if (execution.status.value.state == ScriptSessionState.STOPPED) execution.status.value
                else ScriptSessionStatus(ScriptSessionState.STOPPED, reason = cancelled.message)
            currentCoroutineContext().ensureActive()
        } catch (failure: Exception) {
            mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
        } finally {
            withContext(NonCancellable) {
                collector.cancelAndJoin()
                if (execution.status.value.state in setOf(ScriptSessionState.COMPLETED, ScriptSessionState.FAILED, ScriptSessionState.STOPPED)) {
                    mutableStatus.value = execution.status.value
                }
                if (closing || preparingCapture || mutableStatus.value.state == ScriptSessionState.COMPLETED) closeCapture()
                val outcome = mutableStatus.value
                diagnostics.record(ScriptDiagnostic("lifecycle", null, outcome.reason ?: outcome.failure?.message ?: outcome.state.name,
                    System.nanoTime(), outcome = outcome.state.name))
                logEvents.close()
                logWriter?.join()
                logFailure?.let { mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, it) }
                val final = mutableStatus.value
                try { withContext(Dispatchers.IO) {
                    journal?.finish(final.state.name, final.reason, final.failure, droppedEvents.get())
                } } catch (failure: Exception) {
                    android.util.Log.e("ScriptRunLogs", "Não foi possível finalizar o histórico", failure)
                    closing = true
                    mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
                }
                journal = null
            }
        }
    }

    /** Retries the same immutable revision and saved round over the live target app, e.g. from the floating button. */
    fun retry(): Boolean = requestRetry(RetryRequest())

    override fun recoveryChoices(): List<ScriptRecoveryChoice> {
        val recovery = (recoveryCheckpoint?.get("state") as? JsonObject)?.get("recovery") as? JsonObject ?: return emptyList()
        if (recovery["answer"] != null) return emptyList()
        return (recovery["choices"] as? JsonArray)?.take(8)?.mapNotNull {
            val option = it as? JsonObject ?: return@mapNotNull null
            val id = (option["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val label = (option["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ScriptRecoveryChoice(id, label.take(256))
        }.orEmpty()
    }

    override fun resolveRecovery(choice: String): Boolean {
        if (recoveryChoices().none { it.id == choice }) return false
        return requestRetry(RetryRequest(choice, recoveryCheckpoint))
    }

    private fun requestRetry(request: RetryRequest): Boolean {
        if (closing || preparingCapture || job?.isActive != true ||
            status.value.state !in setOf(ScriptSessionState.FAILED, ScriptSessionState.STOPPED) ||
            !retryQueued.compareAndSet(false, true)) return false
        if (retryRequests.trySend(request).isSuccess) return true
        retryQueued.set(false)
        return false
    }

    override fun pause(): Boolean = execution.pause().also { accepted ->
        diagnostics.record(ScriptDiagnostic("control", null, "Pausa solicitada", System.nanoTime(), outcome = accepted.toString()))
    }
    override fun resume(): Boolean = execution.resume().also { accepted ->
        diagnostics.record(ScriptDiagnostic("control", null, "Continuação solicitada", System.nanoTime(), outcome = accepted.toString()))
    }

    /** Cancels active work; cleanup is awaited separately by the owning service. */
    fun stop(reason: String = "Interrompido pelo usuário") {
        closing = true
        if (job?.isActive == true) diagnostics.record(ScriptDiagnostic("control", null, reason, System.nanoTime(), outcome = "stop_requested"))
        execution.stop(reason)
        job?.cancel(CancellationException(reason))
    }

    /** Waits for detector and projection release before the resource lease can be handed over. */
    suspend fun stopAndJoin() {
        stop()
        job?.join()
        if (projectionFailure != null) {
            recorder.stopProjection()
            projectionFailure = null
        }
        dispatcher.close()
    }
}
