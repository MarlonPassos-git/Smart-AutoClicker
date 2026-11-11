package com.buzbuz.smartautoclicker.core.script.runtime.api

import com.buzbuz.smartautoclicker.core.script.runtime.session.SessionAutomationHost
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptApiException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** API v1 transport. Instances belong to one session and are called through its serialized gate. */
class ScreenAutomationHost(
    private val backend: ScreenAutomationBackend,
    private val resumeCheck: suspend (ScreenAutomationBackend) -> Boolean,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val startParameters: JsonObject = JsonObject(emptyMap()),
    private val onDiagnostic: (ScriptDiagnostic) -> Unit = {},
    initialResumeQuery: JsonObject? = null,
    private val referenceProfile: ScreenProfile? = null,
    private val detailedDiagnostics: () -> Boolean = { false },
) : SessionAutomationHost {
    private var sequence = 0L
    private var frame: Long? = null
    private var size: ScreenSize? = null
    private var stage: String? = null
    private var resumeQuery = initialResumeQuery
    private var pausedAt: Long? = null
    private var pausedNanos = 0L
    private var resumeGeneration = 0L

    override suspend fun call(requestJson: String): String {
        currentCoroutineContext().ensureActive()
        require(requestJson.length <= 262144) { "request.length=${requestJson.length}; expected at most 262144 characters" }
        val request = Json.parseToJsonElement(requestJson).jsonObject
        require(request.int("apiVersion") == 1) { "apiVersion=${request["apiVersion"]}; expected 1" }
        val operation = request.string("operation")
        if (!detailedDiagnostics()) return perform(operation, request).toString()
        val startedAt = monotonicNanos()
        var response: JsonObject? = null
        var outcome = "success"
        try {
            response = perform(operation, request)
            return response.toString()
        } catch (failure: Throwable) {
            outcome = when (failure) {
                is ScriptStopRequested -> "stopped"
                is CancellationException -> "cancelled"
                is ScriptApiException -> failure.code
                else -> "failed"
            }
            throw failure
        } finally {
            val completedAt = monotonicNanos()
            val diagnosticOperation = operation.take(128)
            onDiagnostic(ScriptDiagnostic("operation", stage, diagnosticOperation, completedAt,
                diagnosticOperation, completedAt - startedAt, outcome, operationDetails(operation, request, response)))
        }
    }

    private suspend fun perform(operation: String, request: JsonObject): JsonObject {
        val response = when (operation) {
            "observe" -> observe()
            "read" -> read(request)
            "readAll" -> readAll(request)
            "profile" -> {
                requireFrame(request)
                val profile = checkNotNull(referenceProfile) { "screen: no calibrated reference profile in this package" }
                profile.resolve(checkNotNull(size))
            }
            "tap" -> {
                if (request.containsKey("frame")) requireFrame(request)
                backend.tap(point(request), request.duration())
                invalidateObservations()
                success()
            }
            "tapSequence" -> {
                requireFrame(request)
                backend.tapSequence(tapSequence(request))
                invalidateObservations()
                success()
            }
            "swipe" -> {
                if (request.containsKey("frame")) requireFrame(request)
                backend.swipe(point(request.getValue("from").jsonObject), point(request.getValue("to").jsonObject), request.duration())
                invalidateObservations()
                success()
            }
            "sleep" -> { delay(request.duration()); success() }
            "zoom" -> {
                if (request.containsKey("frame")) requireFrame(request)
                val directionName = request.string("direction")
                val direction = ScreenZoomDirection.entries.find { it.name.lowercase() == directionName }
                    ?: throw IllegalArgumentException("direction=$directionName; expected in or out")
                val intensity = request.int("intensityPx")
                require(intensity in 1..10000) { "intensityPx=$intensity; expected 1..10000 pixels" }
                backend.zoom(point(request.getValue("center").jsonObject), intensity, request.duration(), direction)
                invalidateObservations()
                success()
            }
            "gesture" -> {
                if (request.containsKey("frame")) requireFrame(request)
                backend.gesture(gesture(request))
                invalidateObservations()
                success()
            }
            "now" -> buildJsonObject {
                put("activeNanos", (pausedAt ?: monotonicNanos()) - pausedNanos)
                frame?.let { put("frame", it) }
            }
            "parameters" -> buildJsonObject { put("values", startParameters) }
            "resumeWhen" -> {
                resumeQuery = request["query"] as? JsonObject
                    ?: throw IllegalArgumentException("resumeWhen.query: expected a recognition query object")
                success()
            }
            "stop" -> {
                val reason = request.string("message")
                require(reason.isNotBlank() && reason.length <= 4096) { "stop.message: expected 1..4096 nonblank characters" }
                onDiagnostic(ScriptDiagnostic("stop", stage, reason, monotonicNanos()))
                throw ScriptStopRequested(reason)
            }
            "stage", "log" -> {
                val message = request.string("message")
                require(message.length <= 4096) { "message.length=${message.length}; expected at most 4096 characters" }
                if (operation == "stage") {
                    val name = request.string("name")
                    require(name.isNotBlank() && name.length <= 128) { "stage.name=$name; expected 1..128 nonblank characters" }
                    stage = name
                }
                onDiagnostic(ScriptDiagnostic(operation, stage, message, monotonicNanos()))
                success()
            }
            else -> throw IllegalArgumentException("operation=$operation; expected a supported API v1 operation")
        }
        currentCoroutineContext().ensureActive()
        return response
    }

    override suspend fun releasePointers() = backend.releasePointers()

    override suspend fun pauseOperationClock() {
        if (pausedAt == null) {
            pausedAt = monotonicNanos()
            resumeGeneration++
        }
    }

    override suspend fun resumeOperationClock() {
        pausedAt?.let { pausedNanos += monotonicNanos() - it }
        pausedAt = null
    }

    override suspend fun invalidateObservations() {
        frame = null
        size = null
        backend.invalidateObservation()
    }

    override suspend fun revalidate(): Boolean {
        val query = resumeQuery ?: return resumeCheck(backend)
        return try {
            val observation = observe()
            val response = read(JsonObject(query + ("frame" to observation.getValue("frame"))))
            response["status"]?.jsonPrimitive?.content == "found"
        } finally {
            invalidateObservations()
        }
    }

    private suspend fun observe(): JsonObject {
        invalidateObservations()
        val captured = backend.capture()
        check(captured.width > 0 && captured.height > 0) { "Capture returned invalid screen dimensions: $captured" }
        size = captured
        frame = ++sequence
        return buildJsonObject {
            put("status", "success")
            put("frame", sequence)
            put("resumeGeneration", resumeGeneration)
            put("monotonicNanos", monotonicNanos())
            put("width", captured.width)
            put("height", captured.height)
        }
    }

    private suspend fun read(request: JsonObject): JsonObject {
        requireFrame(request)
        return recognize(recognitionQuery(request))
    }

    private suspend fun readAll(request: JsonObject): JsonObject {
        requireFrame(request)
        val values = request["queries"] as? JsonArray
        require(values != null && values.size in 1..64) { "readAll.queries: expected 1..64 recognition queries" }
        // Validate the complete group before starting native work. One API checkpoint owns the whole frame.
        val queries = values.mapIndexed { index, value ->
            val query = value as? JsonObject
                ?: throw IllegalArgumentException("readAll.queries[$index]=$value; expected a recognition query object")
            recognitionQuery(query)
        }
        val results = queries.map { query ->
            yield()
            recognize(query)
        }
        return buildJsonObject { put("results", JsonArray(results)) }
    }

    private fun recognitionQuery(request: JsonObject): RecognitionQuery {
        val kindName = request.string("kind")
        val kind = RecognitionKind.entries.find { it.name.lowercase() == kindName }
            ?: throw IllegalArgumentException("kind=$kindName; expected image, text, number or color")
        val area = request.getValue("region").jsonObject
        val region = ScreenRegion(area.int("left"), area.int("top"), area.int("right"), area.int("bottom"))
        val currentSize = checkNotNull(size)
        require(region.left >= 0 && region.top >= 0 && region.right > region.left && region.bottom > region.top &&
            region.right <= currentSize.width && region.bottom <= currentSize.height) {
            "region=$region; expected a nonempty region within $currentSize"
        }
        val threshold = request.int("threshold")
        require(threshold in 0..100) { "threshold=$threshold; expected 0..100" }
        val reference = request["reference"]?.jsonPrimitive?.content
        if (kind != RecognitionKind.NUMBER) require(!reference.isNullOrBlank()) {
            "reference: expected a nonempty named resource for $kindName"
        }
        return RecognitionQuery(kind, region, threshold, reference)
    }

    private suspend fun recognize(query: RecognitionQuery): JsonObject {
        val result = backend.recognize(query)
        val status = if (query.kind == RecognitionKind.NUMBER && result.status == RecognitionStatus.FOUND &&
            (result.number == null || !result.number.isFinite())) RecognitionStatus.INVALID_READING else result.status
        return buildJsonObject {
            put("frame", checkNotNull(frame))
            put("status", status.name.lowercase())
            if (result.confidence.isFinite()) put("confidence", result.confidence)
            if (status == RecognitionStatus.FOUND) {
                result.position?.let { put("x", it.x); put("y", it.y) }
                result.size?.let { put("width", it.width); put("height", it.height) }
                result.number?.let { if (it.isFinite()) put("number", it) }
            }
        }
    }

    private fun requireFrame(request: JsonObject) {
        val requested = (request["frame"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        require(requested != null) { "frame=${request["frame"]}; expected an observation frame integer" }
        if (frame == null || requested != frame) throw ScriptApiException("stale_observation",
            "frame=$requested; expected the current frame $frame; observe again")
    }

    private fun point(request: JsonObject): ScreenPoint {
        val point = ScreenPoint(request.int("x"), request.int("y"))
        require(point.x >= 0 && point.y >= 0) { "position=$point; expected nonnegative screen coordinates" }
        return point
    }

    private fun success() = buildJsonObject { put("status", "success") }
    private fun tapSequence(request: JsonObject): List<ScreenTapStep> {
        val values = request["steps"] as? JsonArray
        require(values != null && values.size in 1..64) { "steps: expected 1..64 taps totalling at most 1000 milliseconds" }
        val steps = values.mapIndexed { index, value ->
            val step = value as? JsonObject ?: throw IllegalArgumentException("steps[$index]=$value; expected a tap object")
            val at = step["at"] as? JsonObject ?: throw IllegalArgumentException("steps[$index].at=${step["at"]}; expected x/y coordinates")
            val press = step.int("pressMs").toLong()
            val after = step.int("afterMs").toLong()
            require(press in 1..1000 && after in 0..1000) {
                "steps[$index].pressMs=$press, afterMs=$after; expected press 1..1000 and after 0..1000 milliseconds"
            }
            val position = point(at)
            val bounds = checkNotNull(size)
            require(position.x < bounds.width && position.y < bounds.height) {
                "steps[$index].at=$position; expected coordinates inside $bounds"
            }
            ScreenTapStep(position, press, after)
        }
        val total = steps.sumOf { it.pressMs + it.afterMs }
        require(total <= 1000) { "steps.durationMs=$total; expected at most 1000 milliseconds per cooperative block" }
        return steps
    }
    private fun gesture(request: JsonObject): List<ScreenGestureFinger> {
        val fingers = request["fingers"] as? JsonArray
        require(fingers != null && fingers.size in 1..10) { "fingers: expected 1..10 gesture fingers" }
        return fingers.mapIndexed { index, value ->
            val finger = value as? JsonObject ?: throw IllegalArgumentException("fingers[$index]: expected an object")
            fun duration(field: String): Long {
                if (!finger.containsKey(field)) return 0L
                val amount = (finger[field] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    ?: throw IllegalArgumentException("fingers[$index].$field=${finger[field]}; expected integer milliseconds")
                require(amount in 0..60000) { "fingers[$index].$field=$amount; expected 0..60000 milliseconds" }
                return amount
            }
            val hold = if (!finger.containsKey("keepDown")) false else
                (finger["keepDown"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: throw IllegalArgumentException("fingers[$index].keepDown=${finger["keepDown"]}; expected a boolean")
            require(!hold || index == 0) { "fingers[$index].keepDown=true; only the first finger can remain held" }
            val stepValues = if (!finger.containsKey("steps")) JsonArray(emptyList()) else finger["steps"] as? JsonArray
                ?: throw IllegalArgumentException("fingers[$index].steps: expected an array")
            val steps = stepValues.also {
                require(it.size <= 64) { "fingers[$index].steps.size=${it.size}; expected at most 64" }
            }.map { stepValue ->
                val step = stepValue as? JsonObject ?: throw IllegalArgumentException("fingers[$index].steps: expected objects")
                ScreenGestureStep(step["to"]?.jsonObject?.let(::point), step.duration())
            }
            val startDelay = duration("startDelayMs")
            val initialHold = duration("holdMs")
            val total = startDelay + (if (initialHold == 0L && steps.isEmpty()) 50L else initialHold) + steps.sumOf { it.durationMs }
            require(total <= 60000) { "fingers[$index].durationMs=$total; expected at most 60000 milliseconds" }
            ScreenGestureFinger(point(finger.getValue("start").jsonObject), startDelay, initialHold, steps, hold)
        }
    }
    private fun JsonObject.string(field: String): String = (get(field) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$field: expected a string")
    private fun JsonObject.int(field: String): Int = (get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: throw IllegalArgumentException("$field=${get(field)}; expected an integer")
    private fun JsonObject.duration(): Long {
        val duration = (get("durationMs") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        require(duration != null && duration in 1..60000) { "durationMs=$duration; expected 1..60000 milliseconds" }
        return duration
    }
}
