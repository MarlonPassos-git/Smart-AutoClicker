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
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.session.*
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Projection lifetime owned by LocalService's shared resource lease. One driver per script run. */
class ScriptServiceDriver(
    context: Context,
    private val recorder: DisplayRecorder,
    private val display: DisplayConfigManager,
    actions: AndroidActionExecutor,
    models: OCRModelsRepository,
    processingTag: String,
    createDetector: () -> ImageDetector = { checkNotNull(NativeDetector.newInstance()) { "Biblioteca de detecção indisponível" } },
    runtime: ScriptRuntime? = null,
) : ScriptRunController {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val execution = PackageScriptExecution(
        ScriptPackageRepository(File(context.filesDir, "scripts/packages")), dispatcher, actions,
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
        }, processingTag, runtime,
    )
    private val mutableStatus = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.PREPARING))
    override val status = mutableStatus.asStateFlow()
    override val diagnostics = execution.diagnostics
    private var job: Job? = null
    private var closing = false
    private var projectionFailure: Exception? = null
    private val orientationListener: (Context) -> Unit = {
        stop("Orientação da tela alterada; inicie novamente na orientação calibrada")
    }

    /** Starts after foreground-service permission and exclusive ownership have been established. */
    fun start(scope: CoroutineScope, resultCode: Int, data: Intent, storageId: String, answersJson: String) {
        check(job == null) { "Script service driver can only start once" }
        job = scope.launch(start = CoroutineStart.LAZY) {
            val collector = launch { execution.status.collect { mutableStatus.value = it } }
            try {
                display.addOrientationListener(orientationListener)
                val answers = Json.parseToJsonElement(answersJson) as? JsonObject
                    ?: error("Parâmetros de início devem ser um objeto JSON")
                recorder.startProjection(resultCode, data) { if (!closing) stop("Captura de tela encerrada") }
                recorder.startScreenRecord(display.displayConfig.sizePx)
                delay(500) // Let the configuration activity leave the captured screen before the first observation.
                execution.run(storageId, answers)
            } catch (cancelled: CancellationException) {
                mutableStatus.value = if (execution.status.value.state == ScriptSessionState.STOPPED) execution.status.value
                    else ScriptSessionStatus(ScriptSessionState.STOPPED, reason = cancelled.message)
            } catch (failure: Exception) {
                mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
            } finally {
                withContext(NonCancellable) {
                    collector.cancelAndJoin()
                    if (execution.status.value.state in setOf(ScriptSessionState.COMPLETED, ScriptSessionState.FAILED, ScriptSessionState.STOPPED)) {
                        mutableStatus.value = execution.status.value
                    }
                    closing = true
                    display.removeOrientationListener(orientationListener)
                    try { recorder.stopProjection() }
                    catch (failure: Exception) {
                        projectionFailure = failure
                        mutableStatus.value = ScriptSessionStatus(ScriptSessionState.FAILED, failure)
                    } finally { dispatcher.close() }
                }
            }
        }
        job?.start()
    }

    override fun pause(): Boolean = execution.pause()
    override fun resume(): Boolean = execution.resume()

    /** Cancels active work; cleanup is awaited separately by the owning service. */
    fun stop(reason: String = "Interrompido pelo usuário") {
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
