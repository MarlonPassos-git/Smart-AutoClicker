package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantityDeploymentTest {
    private lateinit var detector: ImageDetector
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Before fun loadModels() {
        detector = NativeDetector.newInstance() ?: error("Native detector unavailable")
        detector.init()
        val (detection, recognition) = InstrumentationRegistry.getInstrumentation().targetContext.extractTestOcrModels()
        assertTrue(detector.loadTextDetectionModels(detection, recognition))
    }

    @After fun close() = detector.close()

    @Test fun readsTheLeftmostAvailableQuantityAndLocatesTheHeroBoundary() = withImage("army_start.png") { bitmap ->
        val quantity = detector.detectNumber(Rect(0, 35, bitmap.width, 85), 80, NumberFormatType.POSITIVE_QUANTITY)
        assertTrue("Quantity confidence=${quantity.confidenceRate}", quantity.isDetected)
        assertEquals(25.0, quantity.numberDetected!!, 0.0)
        assertTrue(quantity.position.x < 250)
        val firstHero = firstHeroPosition(bitmap)
        assertTrue("First hero x=$firstHero must follow both troops", firstHero in 400..700)
    }

    @Test fun mixedArmyIncludesLeftEdgeAndSingleUnitCardsBeforeTheHeroes() = withImage("mixed_army.png") { bitmap ->
        val quantity = detector.detectNumber(Rect(0, 35, bitmap.width, 85), 80, NumberFormatType.POSITIVE_QUANTITY)
        assertTrue("Quantity confidence=${quantity.confidenceRate}", quantity.isDetected)
        assertEquals(25.0, quantity.numberDetected!!, 0.0)
        assertTrue("First troop x=${quantity.position.x}", quantity.position.x < 200)
        val firstHero = firstHeroPosition(bitmap)
        assertTrue("First hero x=$firstHero must follow all ten troop cards", firstHero in 1250..1550)
        val singleUnit = detector.detectNumber(Rect(1000, 35, 1300, 85), 80, NumberFormatType.POSITIVE_QUANTITY)
        assertTrue("Single unit confidence=${singleUnit.confidenceRate}", singleUnit.isDetected)
        assertEquals(1.0, singleUnit.numberDetected!!, 0.0)
    }

    @Test fun readsTwentyNineAndRejectsTheDepletedCard() {
        withImage("quantity_29.png") { bitmap ->
            val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80, NumberFormatType.POSITIVE_QUANTITY)
            assertTrue(result.isDetected)
            assertEquals(29.0, result.numberDetected!!, 0.0)
        }
        withImage("quantity_zero.png") { bitmap ->
            assertFalse(detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80, NumberFormatType.POSITIVE_QUANTITY).isDetected)
        }
    }

    @Test fun quantityModeRequiresAPositiveIntegerWithAnExplicitMultiplierPrefix() {
        for ((text, expected) in listOf("x1" to 1.0, "X29" to 29.0, "xI" to 1.0, "x0" to null, "29" to null, "x2.5" to null)) {
            val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, 20f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = 56f; typeface = Typeface.MONOSPACE
                })
            }
            detector.setScreenBitmap(bitmap, "quantity-$text")
            try {
                val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80, NumberFormatType.POSITIVE_QUANTITY)
                if (expected == null) assertFalse("$text must not be a quantity", result.isDetected)
                else { assertTrue("$text confidence=${result.confidenceRate}", result.isDetected); assertEquals(expected, result.numberDetected!!, 0.0) }
            } finally { detector.releaseScreenBitmap(bitmap); bitmap.recycle() }
        }
    }

    @Test fun attackInterfaceGuardRecognizesLootLabelAndRejectsVillage() {
        for ((name, expected) in listOf("battle_label.png" to true, "village_label.png" to false)) {
            withImage(name) { bitmap ->
                val result = detector.detectText(listOf("Saque"), "latin", Rect(0, 0, bitmap.width, bitmap.height), 80)
                assertEquals("$name confidence=${result.confidenceRate}", expected, result.isDetected)
            }
        }
    }

    @Test fun quantityAtAnOcrWindowBoundaryKeepsEveryDigitAndItsScreenPosition() {
        for (left in listOf(585, 1185, 1785)) {
            val bitmap = Bitmap.createBitmap(2200, 100, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = 56f; typeface = Typeface.MONOSPACE
                }
                drawText("x0", 20f, 72f, paint)
                drawText("x29", left.toFloat(), 72f, paint)
            }
            detector.setScreenBitmap(bitmap, "quantity-window-$left")
            try {
                val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80, NumberFormatType.POSITIVE_QUANTITY)
                assertTrue("left=$left confidence=${result.confidenceRate}", result.isDetected)
                assertEquals(29.0, result.numberDetected!!, 0.0)
                assertTrue("left=$left position=${result.position.x}", result.position.x in left..left + 130)
            } finally { detector.releaseScreenBitmap(bitmap); bitmap.recycle() }
        }
    }

    private fun withImage(name: String, test: (Bitmap) -> Unit) {
        val bitmap = assets.open("quantity_calibration/$name").use(BitmapFactory::decodeStream)
        detector.setScreenBitmap(bitmap, name)
        try { test(bitmap) } finally { detector.releaseScreenBitmap(bitmap); bitmap.recycle() }
    }

    private fun firstHeroPosition(bitmap: Bitmap): Int {
        val references = JSONArray(assets.open("quantity_calibration/hero_references.json").bufferedReader().use { it.readText() })
        var firstHero = bitmap.width
        for (index in 0 until references.length()) {
            val info = references.getJSONObject(index)
            val reference = assets.open("quantity_calibration/${info.getString("name")}").use(BitmapFactory::decodeStream)
            try {
                val result = detector.detectImage(reference, info.getInt("width"), info.getInt("height"), Rect(0, 45, bitmap.width, 175), 20)
                if (result.isDetected) firstHero = minOf(firstHero, result.position.x - result.size.x / 2)
            } finally { reference.recycle() }
        }
        return firstHero
    }
}
