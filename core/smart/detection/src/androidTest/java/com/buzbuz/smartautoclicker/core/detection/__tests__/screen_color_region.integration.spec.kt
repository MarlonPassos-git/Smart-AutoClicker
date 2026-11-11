package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks template color validation across small regions, extensive same-frame use and frame replacement. */
@RunWith(AndroidJUnit4::class)
class ScreenColorRegionTest {
    @Test
    fun repeatedSmallRegionsAndWholeScreenSearchKeepTheSameMatchAndConfidence() {
        val frame = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        val random = Random(2026)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            frame.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        }
        val template = Bitmap.createBitmap(frame, 10, 12, 8, 6)
        val detector = checkNotNull(NativeDetector.newInstance())
        try {
            detector.init()
            detector.setScreenBitmap(frame, "color-region-pattern")
            try {
                // Exercise cumulative small-region work as well as subsequent reuse on the same frame.
                repeat(120) {
                    val result = detector.detectImage(template, 8, 6, Rect(10, 12, 18, 18), 8)
                    assertTrue(result.isDetected)
                    assertEquals(Point(14, 15), result.position)
                    assertEquals(Point(8, 6), result.size)
                    assertEquals(1.0, result.confidenceRate, 0.0001)
                }
                val result = detector.detectImage(template, 8, 6, Rect(0, 0, 80, 60), 8)
                assertTrue(result.isDetected)
                assertEquals(Point(14, 15), result.position)
                assertEquals(1.0, result.confidenceRate, 0.0001)
            } finally { detector.releaseScreenBitmap(frame) }
        } finally { detector.close(); template.recycle(); frame.recycle() }
    }

    @Test
    fun replacingAFrameNeverReusesItsPreviousColorsDespiteMatchingGrayTemplates() {
        val frame = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val red = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val green = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val detector = checkNotNull(NativeDetector.newInstance())
        try {
            detector.init()
            for ((color, matching, rejected) in listOf(Triple(Color.RED, red, green), Triple(Color.GREEN, green, red))) {
                frame.eraseColor(color)
                detector.setScreenBitmap(frame, "color-region-replaced")
                try {
                    repeat(30) {
                        val found = detector.detectImage(matching, 10, 10, Rect(4, 7, 14, 17), 1)
                        assertTrue(found.isDetected)
                        val wrongColor = detector.detectImage(rejected, 10, 10, Rect(4, 7, 14, 17), 1)
                        assertFalse(wrongColor.isDetected)
                    }
                } finally { detector.releaseScreenBitmap(frame) }
            }
        } finally { detector.close(); green.recycle(); red.recycle(); frame.recycle() }
    }
}
