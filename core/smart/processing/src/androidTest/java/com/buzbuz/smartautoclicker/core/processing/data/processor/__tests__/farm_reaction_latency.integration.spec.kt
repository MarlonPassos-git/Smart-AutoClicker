package com.buzbuz.smartautoclicker.core.processing.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.database.entity.CompleteScenario
import com.buzbuz.smartautoclicker.core.database.serialization.DeserializerFactory
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.event.TriggerEvent
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.script.android.AndroidScreenAutomationBackend
import com.buzbuz.smartautoclicker.core.script.android.AndroidScriptResource
import com.buzbuz.smartautoclicker.core.script.android.PackageScreenResources
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.QuickJsScriptRuntime
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptProgram
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenAutomationHost
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenProfile
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostics
import com.buzbuz.smartautoclicker.core.script.runtime.session.AutomationSessionCoordinator
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSession
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import com.buzbuz.smartautoclicker.core.script.runtime.session.SessionAutomationHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the real legacy scenario and the example's payment routine on fixed confirmation pixels.
 * Uses only a private test repository and a non-interactive virtual display; no live game or injected gestures.
 */
@RunWith(AndroidJUnit4::class)
class FarmReactionLatencyBenchmark {
    @Test
    fun compareLegacyFarmAndJavaScriptBatchConfirmation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("benchmark") == "true")
        // A window context binds metrics to the isolated reference-size display on API 31+.
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val root = File(context.cacheDir, "farm-benchmark-${UUID.randomUUID()}")
        val legacyFiles = readZip(context.assets.open("farm_reference/legacy-farm.zip.fixture").use { it.readBytes() })
        val document = Json.parseToJsonElement(legacyFiles.getValue("11/11.json").toString(Charsets.UTF_8)).jsonObject
        val entity = checkNotNull(DeserializerFactory.create(document.getValue("version").jsonPrimitive.int))
            .deserializeCompleteScenario(document.getValue("scenario").jsonObject)
        // Exercise the production domain mapper without widening its visibility just for instrumentation.
        @Suppress("UNCHECKED_CAST")
        val legacy = Class.forName("com.buzbuz.smartautoclicker.core.domain.model.scenario.ScenarioMapperKt")
            .getMethod("toDomain", CompleteScenario::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, entity, false) as Triple<Scenario, List<Event>, List<Counter>>
        assertEquals(323, legacy.second.size)
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "farm-benchmark-reference", 2340, 1080, 320, null, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        var frameToRecycle: Bitmap? = null
        val rounds = JSONArray()
        try {
            val frame = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
            frameToRecycle = frame
            val crop = context.assets.open("wall_calibration/batch_confirmation_gold.png").use(BitmapFactory::decodeStream)
            Canvas(frame).drawBitmap(crop, 690f, 240f, null)
            crop.recycle()
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                withContext(dispatcher) {
                    val native = checkNotNull(NativeDetector.newInstance())
                    val templates = legacyFiles.filterKeys { it.endsWith(".png") }.mapKeys { it.key.substringAfter('/') }
                        .mapValues { BitmapFactory.decodeByteArray(it.value, 0, it.value.size) }
                    val repository = ScriptPackageRepository(root)
                    val files = scriptFiles()
                    val stored = repository.importPackage(ByteArrayInputStream(zip(files)))
                    val resources = PackageScreenResources(repository, stored)
                    native.init()
                    try {
                        loadModels(context, native)
                        stored.manifest.resources.keys.forEach(resources::resolve)
                        for ((name, original) in mapOf("batchTitle" to "Condition_7601609419456655687.png",
                            "batchOk" to "Condition_9040311847634740119.png")) {
                            assertTrue("The template pixels must remain equivalent: $name",
                                (resources.resolve(name) as AndroidScriptResource.Image).bitmap.sameAs(templates.getValue(original)))
                        }
                        val scalingContext = context.createWindowContext(display.display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        val scaling = ScalingManager(DisplayConfigManager(scalingContext))
                        val screens = legacy.second.filterIsInstance<ScreenEvent>()
                        val triggers = legacy.second.filterIsInstance<TriggerEvent>()
                        assertEquals(android.graphics.Point(2340, 1080), scaling.startScaling(10000.0, screens))
                        repeat(6) { round ->
                            val paths = listOf("legacy-farm", "script-farm", "script-farm-detailed", "script-farm-uncached", "script-farm-shared-thread", "script-farm-shared-thread-detailed")
                                .let { it.drop(round % it.size) + it.take(round % it.size) }
                            val criteria = mutableMapOf<String, List<Set<String>>>()
                            for (path in paths) {
                                val meter = Samples()
                                val detector = detector(native, meter)
                                val actions = actions(meter)
                                val memoryBefore = memory()
                                val cpuBefore = Process.getElapsedCpuTime()
                                var diagnosticEvents = 0
                                var operationEvents = 0
                                if (path == "legacy-farm") {
                                    val counters = legacy.third.map { counter -> counter.copy(defaultValue = when (counter.counterName) {
                                        "muros_etapa" -> 24.0
                                        "muros_aplicar_etapa" -> 1.0
                                        "muros_lote_total" -> 8_400_000.0
                                        "muros_lote_quantidade" -> 2.0
                                        else -> counter.defaultValue
                                    }) }
                                    repeat(23) { iteration ->
                                        // Recreate state outside the timed region: each sample starts at the same stage.
                                        val processor = ScenarioProcessor("com.buzbuz.smartautoclicker.core.processing.test", detector, scaling, false,
                                            screens, triggers, counters, actions,
                                            bitmapSupplier = { name, width, height ->
                                                checkNotNull(templates[name]).also { assertEquals(width, it.width); assertEquals(height, it.height) }
                                            }, onStopRequested = { error("Unexpected legacy stop") }, progressListener = null)
                                        meter.begin(iteration)
                                        processor.process(frame)
                                        meter.finish()
                                        assertEquals(26.0, processor.processingState.getCounterValue("muros_etapa")!!, 0.0)
                                    }
                                } else {
                                    val backend = AndroidScreenAutomationBackend(detector, actions, { frame }, resources::resolve, "com.buzbuz.smartautoclicker.core.processing.test")
                                    val diagnostics = ScriptDiagnostics().apply { setDetailed(path.endsWith("detailed")) }
                                    val delegate = ScreenAutomationHost(backend, { false }, onDiagnostic = diagnostics::record,
                                        referenceProfile = ScreenProfile.parse(stored.manifest.document.getValue("screen")),
                                        detailedDiagnostics = { diagnostics.isDetailedEnabled })
                                    val host = object : SessionAutomationHost by delegate {
                                        override suspend fun call(requestJson: String): String {
                                            val request = Json.parseToJsonElement(requestJson).jsonObject
                                            if (request["operation"]?.jsonPrimitive?.content == "log") {
                                                when (val marker = request.getValue("message").jsonPrimitive.content) {
                                                    "benchmark-end" -> meter.finish()
                                                    else -> if (marker.startsWith("benchmark-start:")) meter.begin(marker.substringAfter(':').toInt())
                                                        else return delegate.call(requestJson)
                                                }
                                                return "{\"status\":\"success\"}"
                                            }
                                            val startedAt = SystemClock.elapsedRealtimeNanos()
                                            val response = delegate.call(requestJson)
                                            meter.hostCalls.put(JSONObject()
                                                .put("operation", request.getValue("operation").jsonPrimitive.content)
                                                .put("durationNs", SystemClock.elapsedRealtimeNanos() - startedAt)
                                                .put("responseCharacters", response.length))
                                            return response
                                        }
                                    }
                                    val sourceFiles = if (path == "script-farm-uncached") files +
                                        ("screen.js" to context.assets.open("farm_reference/screen-before-geometry-cache.js.fixture").use { it.readBytes() })
                                        else files
                                    val sources = sourceFiles.filterKeys { it.endsWith(".js") }.mapValues { it.value.toString(Charsets.UTF_8) } +
                                        ("benchmark.js" to """
                                            import { confirmPayment } from './walls.js';
                                            import { log } from 'klickr';
                                            for (let iteration=0; iteration<23; iteration++) {
                                                await log('benchmark-start:'+iteration);
                                                await confirmPayment('gold',8400000,true);
                                                await log('benchmark-end');
                                            }
                                        """.trimIndent())
                                    // Compare the old Default layout with the shared resource thread now used by ScriptServiceDriver.
                                    val scriptDispatcher = if (path.startsWith("script-farm-shared-thread")) dispatcher else Dispatchers.Default
                                    val session = ScriptSession(QuickJsScriptRuntime(scriptDispatcher), AutomationSessionCoordinator(dispatcher, {}))
                                    session.run(ScriptProgram("benchmark.js", sources), host)
                                    assertEquals(session.status.value.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, session.status.value.state)
                                    diagnosticEvents = diagnostics.snapshot().size
                                    operationEvents = diagnostics.snapshot().count { it.kind == "operation" }
                                    if (path.endsWith("detailed")) assertTrue(operationEvents > 0)
                                    else assertEquals(0, operationEvents)
                                }
                                val cpuMs = Process.getElapsedCpuTime() - cpuBefore
                                assertEquals(20, meter.rows.length())
                                criteria[path] = meter.criteria
                                rounds.put(JSONObject().put("round", round).put("path", path)
                                    .put("scriptDispatcher", if (path == "legacy-farm") "none" else if (path.startsWith("script-farm-shared-thread")) "single resource thread" else "Dispatchers.Default")
                                    .put("samples", meter.rows)
                                    .put("reaction", summary(meter.reactions)).put("retainedDiagnosticEvents", diagnosticEvents)
                                    .put("retainedOperationEvents", operationEvents).put("cpuProcessMsIncludingWarmupAndSetup", cpuMs)
                                    .put("memoryBefore", memoryBefore).put("memoryAfter", memory()))
                            }
                            val expected = criteria.getValue("legacy-farm").first()
                            assertEquals(4, expected.size)
                            criteria.values.flatten().forEach { actual ->
                                assertEquals("Every sample must retain the same native regions, thresholds, formats and templates", expected, actual)
                            }
                        }
                    } finally {
                        native.close(); resources.close(); templates.values.forEach(Bitmap::recycle)
                    }
                }
            }
            val report = JSONObject().put("schemaVersion", 1).put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first()).put("clock", "elapsedRealtimeNanos")
                .put("legacyEvents", legacy.second.size).put("stage", "muros_etapa=24; muros_lote_total=8400000")
                .put("fixture", "wall_calibration/batch_confirmation_gold.png at 690,240 on 2340x1080")
                .put("warmupPerRound", 3).put("samplesPerRound", 20).put("recognitionFailures", 0)
                .put("scope", "available bitmap to fake AndroidActionExecutor; no OS capture, gesture injection or game response")
                .put("scriptDispatcher", "Dispatchers.Default except script-farm-shared-thread* on single resource thread").put("androidDispatcher", "single resource thread")
                .put("diagnosticViewerMeasured", false)
                .put("rounds", rounds)
            // Keep each Binder transaction bounded. Unique keys survive UTP's status aggregation and the runner's
            // removal of the test APK/cache after completion. Report transport happens outside measured samples.
            report.toString().chunked(60_000).forEachIndexed { index, part ->
                instrumentation.sendStatus(0, Bundle().apply { putString("benchmarkJsonPart%04d".format(index), part) })
            }
        } finally {
            frameToRecycle?.recycle(); display.release(); root.deleteRecursively()
        }
    }

    private class Samples {
        val rows = JSONArray()
        val reactions = mutableListOf<Long>()
        val criteria = mutableListOf<Set<String>>()
        private var iteration = 0
        private var start = 0L
        @Volatile private var gesture = 0L
        @Volatile private var gestures = 0
        var calls = JSONArray()
        var hostCalls = JSONArray()
        fun begin(index: Int) { iteration = index; gesture = 0; gestures = 0; calls = JSONArray(); hostCalls = JSONArray(); start = SystemClock.elapsedRealtimeNanos() }
        fun dispatched() { gesture = SystemClock.elapsedRealtimeNanos(); gestures++ }
        fun finish() {
            assertEquals("Only the confirmed payment reaches the executor", 1, gestures)
            if (iteration >= 3) {
                reactions.add(gesture - start)
                criteria.add((0 until calls.length()).map { calls.getJSONObject(it) }
                    .filter { it.has("criteria") }.map { it.getString("criteria") }.toSet())
                rows.put(JSONObject().put("reactionNs", gesture - start).put("nativeCalls", calls).put("hostCalls", hostCalls))
            }
        }
    }

    private fun detector(native: ImageDetector, meter: Samples): ImageDetector = Proxy.newProxyInstance(
        ImageDetector::class.java.classLoader, arrayOf(ImageDetector::class.java),
    ) { _, method, arguments ->
        val start = SystemClock.elapsedRealtimeNanos()
        val result = try { method.invoke(native, *(arguments ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        val elapsed = SystemClock.elapsedRealtimeNanos() - start
        val row = JSONObject().put("operation", method.name).put("durationNs", elapsed)
        if (result is DetectionResult) {
            row.put("criteria", method.name + ":" + (arguments ?: emptyArray()).joinToString("|") { argument -> when (argument) {
                is Bitmap -> "image[${argument.width},${argument.height}]"
                is android.graphics.Rect -> "${argument.left},${argument.top},${argument.right},${argument.bottom}"
                else -> argument.toString()
            } })
            row.put("found", result.isDetected).put("confidence", result.confidenceRate)
            result.numberDetected?.let { row.put("number", it) }
        }
        meter.calls.put(row)
        result
    } as ImageDetector

    private fun actions(meter: Samples): AndroidActionExecutor = Proxy.newProxyInstance(
        AndroidActionExecutor::class.java.classLoader, arrayOf(AndroidActionExecutor::class.java),
    ) { _, method, _ -> when (method.name) {
        "getHeldPointer" -> null
        "resetState", "releaseHeldPointer", "dispatchPixelWorkaroundGestureIfNeeded" -> Unit
        "dispatchBuiltGesture" -> { meter.dispatched(); Unit }
        else -> error("No real Android action permitted: ${method.name}")
    } } as AndroidActionExecutor

    private fun scriptFiles(): Map<String, ByteArray> {
        val files = mutableMapOf("manifest.json" to resource("manifest.json"))
        for (name in listOf("entry.js", "farm.js", "battle.js", "walls.js", "screen.js", "policy.js", "profile.js")) files[name] = resource(name)
        val manifest = Json.parseToJsonElement(files.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
        manifest.getValue("resources").jsonObject.values.forEach { value ->
            val definition = value.jsonObject
            if (definition.getValue("type").jsonPrimitive.content == "image") {
                val path = definition.getValue("path").jsonPrimitive.content
                files[path] = resource(path)
            }
        }
        return files
    }

    private fun resource(path: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/$path")).use { it.readBytes() }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) { val entry = input.nextEntry ?: break; if (!entry.isDirectory) put(entry.name, input.readBytes()) }
        }
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { output -> files.forEach { (name, bytes) ->
            output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
        } }
    }.toByteArray()

    private fun loadModels(context: Context, detector: ImageDetector) {
        val root = File(context.cacheDir, "farm-benchmark-ocr")
        for (path in listOf("detect", "recognize/latin")) {
            val directory = File(root, path).apply { mkdirs() }
            context.assets.list("models/text/$path")!!.forEach { name ->
                val destination = File(directory, name)
                if (!destination.exists()) context.assets.open("models/text/$path/$name").use { input -> destination.outputStream().use(input::copyTo) }
            }
        }
        assertTrue(detector.loadTextDetectionModels(File(root, "detect").path, mapOf("LATIN" to File(root, "recognize/latin").path)))
    }

    private fun summary(samples: List<Long>): JSONObject {
        val sorted = samples.sorted()
        fun percentile(fraction: Double) = sorted[ceil(sorted.size * fraction).toInt() - 1]
        return JSONObject().put("p50Ns", percentile(0.5)).put("p95Ns", percentile(0.95))
    }

    private fun memory() = JSONObject().put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
        .put("javaUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        .put("pssKiB", Debug.getPss())
}
