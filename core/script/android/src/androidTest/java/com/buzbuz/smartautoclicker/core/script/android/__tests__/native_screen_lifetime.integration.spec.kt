package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeScreenLifetimeIntegrationTest {
    @Test
    fun zeroColorToleranceAcceptsTheExpectedColorAndRejectsTheOppositeColor() = runBlocking {
        verifyFreshSession(Color.GREEN)
    }

    @Test(timeout = 650000)
    fun aFreshNativeSessionAcceptsItsScreenAfterTenMinutesInTheSameProcess() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("longevity") == "true")
        verifyFreshSession(Color.GREEN)
        val started = SystemClock.elapsedRealtime()
        // The existing native decoder evaluates application metadata after 600000 ms in this process.
        // Exercise that real boundary without changing its clock or relaxing its checks.
        repeat(20) {
            delay(30000)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("longevityProgress", "elapsedMs=${SystemClock.elapsedRealtime() - started}")
            })
        }
        delay(10000)
        assertTrue(SystemClock.elapsedRealtime() - started >= 610000)
        verifyFreshSession(Color.BLUE)
    }

    private suspend fun verifyFreshSession(color: Int) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            withContext(dispatcher) {
                val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                val detector = checkNotNull(NativeDetector.newInstance())
                val actions = Proxy.newProxyInstance(AndroidActionExecutor::class.java.classLoader,
                    arrayOf(AndroidActionExecutor::class.java)) { _, method, _ ->
                    error("Recognition must not dispatch an Android action: ${method.name}")
                } as AndroidActionExecutor
                detector.init()
                val backend = AndroidScreenAutomationBackend(detector, actions, { bitmap }, { reference ->
                    AndroidScriptResource.Color(if (reference == "expected") color else color xor 0x00FFFFFF)
                }, "com.buzbuz.smartautoclicker.core.script.android.test")
                try {
                    assertEquals(ScreenSize(40, 40), backend.capture())
                    val query = RecognitionQuery(RecognitionKind.COLOR, ScreenRegion(0, 0, 40, 40), 0, "expected")
                    assertEquals(RecognitionStatus.FOUND, backend.recognize(query).status)
                    assertEquals(RecognitionStatus.NOT_FOUND, backend.recognize(query.copy(reference = "opposite")).status)
                } finally {
                    backend.invalidateObservation()
                    detector.close()
                    bitmap.recycle()
                }
            }
        }
    }
}
