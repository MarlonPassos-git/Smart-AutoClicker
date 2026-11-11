package com.buzbuz.smartautoclicker.core.processing.data.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.action.Click
import com.buzbuz.smartautoclicker.core.domain.model.action.position.CoordinatesValue
import com.buzbuz.smartautoclicker.core.domain.model.action.position.Position
import com.buzbuz.smartautoclicker.core.domain.model.condition.NumberFormatType
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.script.android.AndroidScreenAutomationBackend
import com.buzbuz.smartautoclicker.core.script.android.AndroidScriptResource
import com.buzbuz.smartautoclicker.core.script.runtime.QuickJsScriptRuntime
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptProgram
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenAutomationHost
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostics
import com.buzbuz.smartautoclicker.core.script.runtime.session.AutomationSessionCoordinator
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSession
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import com.buzbuz.smartautoclicker.core.script.runtime.session.SessionAutomationHost
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in comparison of production pipelines on identical pixels, stopping at the gesture boundary.
 * No MediaProjection, Android gesture injection, game animation or polling delay is measured here.
 */
@RunWith(AndroidJUnit4::class)
class ScriptReactionLatencyBenchmark {
    @Test
    fun compareCurrentScenarioAndScriptReaction() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("benchmark") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val frame = context.assets.open("wall_calibration/confirmation_current.png").use(BitmapFactory::decodeStream)
        val rounds = JSONArray()
        try {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                withContext(dispatcher) {
                    val native = checkNotNull(NativeDetector.newInstance())
                    native.init()
                    try {
                        val modelRoot = File(context.cacheDir, "reaction-benchmark-ocr")
                        for (path in listOf("detect", "recognize/latin")) {
                            val directory = File(modelRoot, path).apply { mkdirs() }
                            context.assets.list("models/text/$path")!!.forEach { name ->
                                val destination = File(directory, name)
                                if (!destination.exists()) context.assets.open("models/text/$path/$name").use { input ->
                                    destination.outputStream().use(input::copyTo)
                                }
                            }
                        }
                        assertTrue(native.loadTextDetectionModels(File(modelRoot, "detect").path,
                            mapOf("LATIN" to File(modelRoot, "recognize/latin").path)))
                        repeat(5) { round ->
                            // Alternate order so warm caches and host load don't always favor the second path.
                            val paths = listOf("scenario", "script", "script-grouped")
                                .let { it.drop(round % it.size) + it.take(round % it.size) }
                            for (path in paths) {
                                val meter = ReactionSamples()
                                val detector = TimedDetector(native, meter)
                                val actions = actions(meter)
                                val cpuBefore = Process.getElapsedCpuTime()
                                val memoryBefore = memory()
                                if (path == "scenario") {
                                    val event = event()
                                    val scaling = ScalingManager(DisplayConfigManager(instrumentation.targetContext))
                                    scaling.startScaling(10000.0, listOf(event))
                                    val processor = ScenarioProcessor("com.buzbuz.smartautoclicker.core.processing.test", detector, scaling, false,
                                        listOf(event), emptyList(), emptyList(), actions,
                                        bitmapSupplier = { _, _, _ -> error("No image template in this fixture") },
                                        onStopRequested = { error("Benchmark event unexpectedly disabled") }, progressListener = null)
                                    repeat(23) { iteration ->
                                        meter.begin(iteration)
                                        processor.process(frame)
                                        meter.finish()
                                    }
                                } else {
                                    val diagnostics = ScriptDiagnostics()
                                    val backend = AndroidScreenAutomationBackend(detector, actions, { frame }, {
                                        when (it) {
                                            "green" -> AndroidScriptResource.Color(-10034676)
                                            "cost" -> AndroidScriptResource.Number(com.buzbuz.smartautoclicker.core.detection.NumberFormatType.COMMA_DECIMAL)
                                            else -> error("Unexpected resource $it")
                                        }
                                    }, "com.buzbuz.smartautoclicker.core.processing.test")
                                    val delegate = ScreenAutomationHost(backend, { false }, onDiagnostic = diagnostics::record)
                                    val host = object : SessionAutomationHost by delegate {
                                        override suspend fun call(requestJson: String): String {
                                            val request = Json.parseToJsonElement(requestJson).jsonObject
                                            if (request["operation"]?.jsonPrimitive?.content == "log") {
                                                val marker = request.getValue("message").jsonPrimitive.content
                                                if (marker == "end") meter.finish()
                                                else meter.begin(marker.toInt())
                                                return "{\"status\":\"success\"}"
                                            }
                                            return delegate.call(requestJson)
                                        }
                                    }
                                    val session = ScriptSession(QuickJsScriptRuntime(dispatcher), AutomationSessionCoordinator(dispatcher, {}))
                                    session.run(ScriptProgram("entry.js", mapOf("entry.js" to script(path == "script-grouped"))), host)
                                    assertEquals(session.status.value.failure?.stackTraceToString(),
                                        ScriptSessionState.COMPLETED, session.status.value.state)
                                }
                                assertEquals(20, meter.samples.length())
                                rounds.put(JSONObject().put("round", round).put("path", path)
                                    .put("samples", meter.samples).put("reaction", summary(meter.reactions))
                                    .put("cpuProcessMsIncludingWarmup", Process.getElapsedCpuTime() - cpuBefore)
                                    .put("memoryBefore", memoryBefore).put("memoryAfter", memory()))
                            }
                        }
                    } finally { native.close() }
                }
            }
            val report = JSONObject().put("schemaVersion", 1).put("device", Build.MODEL)
                .put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                .put("clock", "elapsedRealtimeNanos").put("fixture", "wall_calibration/confirmation_current.png")
                .put("width", frame.width).put("height", frame.height).put("warmupPerRound", 3)
                .put("samplesPerRound", 20).put("recognitionFailures", 0)
                .put("scope", "available bitmap to AndroidActionExecutor.dispatchBuiltGesture; no OS capture or gesture injection")
                .put("rounds", rounds)
            val destination = checkNotNull(context.getExternalFilesDir(null)).resolve("script-reaction-latency.json")
            destination.writeText(report.toString(2))
            instrumentation.sendStatus(0, Bundle().apply { putString("benchmarkJson", report.toString()) })
        } finally { frame.recycle() }
    }

    private fun event(): ScreenEvent {
        val eventId = Identifier(databaseId = 1)
        return ScreenEvent(eventId, Identifier(databaseId = 2), "Confirmação", AND, priority = 0, keepDetecting = false, cooldownMs = 0,
            conditions = listOf(
                ScreenCondition.Number(Identifier(databaseId = 3), eventId, "Preço", 80, priority = 0,
                    detectionArea = Rect(655, 882, 850, 916), comparisonOperation = ComparisonOperation.EQUALS,
                    counterValue = CounterOperationValue.Number(4_200_000.0), numberFormatType = NumberFormatType.COMMA_DECIMAL),
                ScreenCondition.Color(Identifier(databaseId = 4), eventId, "Disponível", 7, true, 1, -10034676, Rect(704, 886, 707, 889))),
            actions = listOf(Click(Identifier(databaseId = 5), eventId, priority = 0, pressDuration = 50,
                position = Position.Coordinates(CoordinatesValue.Literal(700), CoordinatesValue.Literal(900)))))
    }

    private fun actions(meter: ReactionSamples): AndroidActionExecutor = Proxy.newProxyInstance(
        AndroidActionExecutor::class.java.classLoader, arrayOf(AndroidActionExecutor::class.java),
    ) { _, method, _ -> when (method.name) {
        "getHeldPointer" -> null
        "resetState", "releaseHeldPointer", "dispatchPixelWorkaroundGestureIfNeeded" -> Unit
        "dispatchBuiltGesture" -> { meter.dispatched(); Unit }
        else -> error("Unexpected Android action ${method.name}")
    } } as AndroidActionExecutor

    private class ReactionSamples {
        val samples = JSONArray()
        val reactions = mutableListOf<Long>()
        private var iteration = 0
        @Volatile private var start = 0L
        @Volatile private var gestureAt = 0L
        var bindNs = 0L
        var numberNs = 0L
        var colorNs = 0L
        var recognitionEnd = 0L
        var binds = 0
        var numbers = 0
        var colors = 0
        @Volatile private var gestures = 0

        fun begin(index: Int) {
            iteration = index
            bindNs = 0; numberNs = 0; colorNs = 0; binds = 0; numbers = 0; colors = 0; gestures = 0
            gestureAt = 0; recognitionEnd = 0
            start = SystemClock.elapsedRealtimeNanos()
        }

        fun dispatched() { gestureAt = SystemClock.elapsedRealtimeNanos(); gestures++ }

        fun finish() {
            assertEquals("Exactly one confirmed gesture", 1, gestures)
            assertEquals(1, binds); assertEquals(1, numbers); assertEquals(1, colors)
            val reaction = gestureAt - start
            if (iteration >= 3) {
                reactions.add(reaction)
                samples.put(JSONObject().put("reactionNs", reaction).put("bindFrameNs", bindNs)
                    .put("numberNs", numberNs).put("colorNs", colorNs)
                    .put("afterRecognitionToGestureNs", gestureAt - recognitionEnd)
                    .put("coordinationAndBridgeNs", reaction - bindNs - numberNs - colorNs)
                    .put("captures", binds).put("ocrCalls", numbers).put("gestures", gestures))
            }
        }
    }

    private class TimedDetector(private val delegate: ImageDetector, private val meter: ReactionSamples) : ImageDetector by delegate {
        override fun setScreenBitmap(screenBitmap: Bitmap, metadata: String) {
            val start = SystemClock.elapsedRealtimeNanos()
            delegate.setScreenBitmap(screenBitmap, metadata)
            meter.bindNs += SystemClock.elapsedRealtimeNanos() - start
            meter.binds++
        }

        override fun detectNumber(detectionArea: Rect, threshold: Int,
            numberFormatType: com.buzbuz.smartautoclicker.core.detection.NumberFormatType): DetectionResult {
            val start = SystemClock.elapsedRealtimeNanos()
            val result = delegate.detectNumber(detectionArea, threshold, numberFormatType)
            meter.recognitionEnd = SystemClock.elapsedRealtimeNanos()
            meter.numberNs += meter.recognitionEnd - start
            meter.numbers++
            assertTrue(result.isDetected)
            assertEquals(4_200_000.0, checkNotNull(result.numberDetected), 0.001)
            return result
        }

        override fun detectColor(conditionColor: Int, detectionArea: Rect, threshold: Int): DetectionResult {
            val start = SystemClock.elapsedRealtimeNanos()
            val result = delegate.detectColor(conditionColor, detectionArea, threshold)
            meter.recognitionEnd = SystemClock.elapsedRealtimeNanos()
            meter.colorNs += meter.recognitionEnd - start
            meter.colors++
            assertTrue(result.isDetected)
            return result
        }
    }

    private fun memory() = JSONObject().put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
        .put("javaUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())

    private fun summary(samples: List<Long>): JSONObject {
        val sorted = samples.sorted()
        fun percentile(fraction: Double) = sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)]
        return JSONObject().put("p50Ns", percentile(0.5)).put("p95Ns", percentile(0.95))
    }

    private fun script(grouped: Boolean): String = """
        import { observe, read, readAll, tap, log } from 'klickr';
        for (let iteration = 0; iteration < 23; iteration++) {
            await log(String(iteration));
            const frame = await observe();
            const priceQuery = {kind:'number',reference:'cost',region:{left:655,top:882,right:850,bottom:916},threshold:80};
            const greenQuery = {kind:'color',reference:'green',region:{left:704,top:886,right:707,bottom:889},threshold:7};
            ${if (grouped) "const [price,green] = await readAll(frame, [priceQuery,greenQuery]);" else "const price = await read(frame, priceQuery); const green = await read(frame, greenQuery);"}
            if (price.status !== 'found' || price.number !== 4200000) throw new Error('Incorrect price');
            if (green.status !== 'found') throw new Error('Unavailable price');
            await tap({x:700,y:900,frame:frame.frame});
            await log('end');
        }
    """.trimIndent()
}
