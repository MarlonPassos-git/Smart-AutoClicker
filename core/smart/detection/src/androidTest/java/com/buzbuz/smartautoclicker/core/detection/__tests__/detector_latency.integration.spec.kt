package com.buzbuz.smartautoclicker.core.detection

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** Opt-in measurement on fixed pixels; never treats device timing as a normal test assertion. */
@RunWith(AndroidJUnit4::class)
class DetectorLatencyBenchmark {
    @Test
    fun measureCurrentWallConfirmationDetector() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("benchmark") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("wall_calibration/confirmation_current.png")
            .use(BitmapFactory::decodeStream)
        val detector = checkNotNull(NativeDetector.newInstance())
        val rounds = JSONArray()
        try {
            detector.init()
            val (detection, recognition) = instrumentation.targetContext.extractTestOcrModels()
            assertTrue(detector.loadTextDetectionModels(detection, recognition))
            repeat(5) { round ->
                val bindSamples = mutableListOf<Long>()
                val numberSamples = mutableListOf<Long>()
                val colorSamples = mutableListOf<Long>()
                repeat(23) { iteration ->
                    val bindStart = SystemClock.elapsedRealtimeNanos()
                    detector.setScreenBitmap(bitmap, "latency-reference-$round-$iteration")
                    val bindNanos = SystemClock.elapsedRealtimeNanos() - bindStart
                    try {
                        val numberStart = SystemClock.elapsedRealtimeNanos()
                        val number = detector.detectNumber(Rect(655, 882, 850, 916), 80, NumberFormatType.COMMA_DECIMAL)
                        val numberNanos = SystemClock.elapsedRealtimeNanos() - numberStart
                        val colorStart = SystemClock.elapsedRealtimeNanos()
                        val color = detector.detectColor(-10034676, Rect(704, 886, 707, 889), 7)
                        val colorNanos = SystemClock.elapsedRealtimeNanos() - colorStart
                        assertTrue(number.isDetected)
                        assertEquals(4_200_000.0, checkNotNull(number.numberDetected), 0.001)
                        assertTrue(color.isDetected)
                        // Each round warms up independently to expose between-round variation.
                        if (iteration >= 3) {
                            bindSamples.add(bindNanos)
                            numberSamples.add(numberNanos)
                            colorSamples.add(colorNanos)
                        }
                    } finally {
                        detector.releaseScreenBitmap(bitmap)
                    }
                }
                rounds.put(JSONObject().put("round", round).put("bindFrame", summary(bindSamples))
                    .put("number", summary(numberSamples)).put("color", summary(colorSamples)))
            }
            val report = JSONObject()
                .put("schemaVersion", 1).put("path", "native-detector-reference")
                .put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first()).put("clock", "elapsedRealtimeNanos")
                .put("fixture", "wall_calibration/confirmation_current.png")
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("warmupPerRound", 3).put("samplesPerRound", 20)
                .put("recognitionFailures", 0).put("rounds", rounds)
            val destination = instrumentation.context.getExternalFilesDir(null)
                ?.resolve("detector-latency-reference.json") ?: error("Benchmark output directory unavailable")
            destination.writeText(report.toString(2))
            // UTP removes the test package afterwards, so also preserve the measurements in its result metrics.
            instrumentation.sendStatus(0, Bundle().apply {
                putString("benchmarkReport", destination.absolutePath)
                putString("benchmarkJson", report.toString())
            })
        } finally {
            detector.close()
            bitmap.recycle()
        }
    }

    private fun summary(samples: List<Long>): JSONObject {
        val sorted = samples.sorted()
        fun percentile(fraction: Double) = sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)]
        return JSONObject().put("samplesNs", JSONArray(samples)).put("p50Ns", percentile(0.5))
            .put("p95Ns", percentile(0.95)).put("minNs", sorted.first()).put("maxNs", sorted.last())
    }
}
