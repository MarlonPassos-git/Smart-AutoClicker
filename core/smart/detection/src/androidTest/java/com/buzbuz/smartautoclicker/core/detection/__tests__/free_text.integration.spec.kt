package com.buzbuz.smartautoclicker.core.detection

import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreeTextIntegrationTest {
    @Test fun readsAccountNamesAndStableIdentityFromRealSamsungCropsWithAbsoluteBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = checkNotNull(NativeDetector.newInstance())
        detector.init()
        try {
            val (detect, models) = context.extractTestOcrModels()
            assertTrue(detector.loadTextDetectionModels(detect, models))
            for ((asset, expected) in listOf("selector" to listOf("MarlonPassoz", "MarlonFull", "login"), "identity" to listOf("#GCY20PCPL"), "selector-title" to listOf("TROCARDEID"), "reward" to emptyList(), "capacity-current" to emptyList())) {
                val bitmap = context.assets.open("multivillage/$asset.png").use { BitmapFactory.decodeStream(it) }
                try {
                    detector.setScreenBitmap(bitmap, context.packageName.removeSuffix(".test"))
                    val blocks = detector.readText("latin", Rect(0, 0, bitmap.width, bitmap.height), 70, 64)
                    android.util.Log.i("FreeTextEvidence", "$asset: $blocks")
                    expected.forEach { text -> assertTrue("$asset missing $text; actual=$blocks", blocks.any { text in it.text }) }
                    if (asset == "capacity-current") {
                        for (left in listOf(287, 290)) {
                            val number = detector.detectNumber(Rect(left, 13, 495, 58), 85, NumberFormatType.COMMA_DECIMAL)
                            assertTrue(number.isDetected)
                            assertEquals(31000000.0, number.numberDetected!!, 0.0)
                        }
                    }
                    if (asset == "reward") {
                        val title = blocks.filter { it.bounds.top < 180 }.sortedBy { it.bounds.left }
                            .joinToString("") { it.text.filter(Char::isLetter).lowercase() }
                        assertEquals("recompensarecebida", title)
                        assertEquals(1, blocks.count { it.text.lowercase() == "ok" })
                    }
                    if (asset == "selector-title") {
                        assertTrue(detector.detectText(listOf("TROCAR DE ID", "TROCARDEID"), "latin",
                            Rect(0, 0, bitmap.width, bitmap.height), 85).isDetected)
                    }
                    assertTrue(blocks.all { it.confidence in 70.0..100.0 && it.bounds.left >= 0 && it.bounds.top >= 0 })
                    assertTrue(detector.readText("latin", Rect(0, 0, bitmap.width, bitmap.height), 70, 1).size <= 1)
                    assertThrows(IllegalArgumentException::class.java) { detector.readText("latin", Rect(-1, 0, 100, 50), 70, 1) }
                } finally { detector.releaseScreenBitmap(bitmap); bitmap.recycle() }
            }
        } finally { detector.close() }
    }
}
