package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel-exact crops from the calibrated 2340×1080 Samsung frames, also at engine quality 1600. */
@RunWith(AndroidJUnit4::class)
class WallCalibrationTest {
    private lateinit var detector: ImageDetector

    @Before fun loadModels() {
        detector = NativeDetector.newInstance() ?: error("Native detector unavailable")
        detector.init()
        val (detection, recognition) = InstrumentationRegistry.getInstrumentation().targetContext.extractTestOcrModels()
        assertTrue(detector.loadTextDetectionModels(detection, recognition))
    }

    @After fun close() { detector.close() }

    @Test fun recognizesCurrentUpgradeConfirmationWithBrightGreenPrice() {
        val context = InstrumentationRegistry.getInstrumentation().context
        withCrop("confirmation_current", 1.0) { _ ->
            for ((asset, area, threshold) in listOf(
                Triple("confirmation_title_reference", Rect(0, 0, 780, 65), 12),
                Triple("confirmation_gold_reference", Rect(870, 880, 930, 945), 10),
            )) {
                val reference = context.assets.open("wall_calibration/$asset.png").use(BitmapFactory::decodeStream)
                try {
                    val result = detector.detectImage(reference, area.width(), area.height(), area, threshold)
                    assertTrue("$asset confidence=${result.confidenceRate}", result.isDetected)
                } finally { reference.recycle() }
            }
            val cost = detector.detectNumber(Rect(655, 882, 850, 916), 80, NumberFormatType.COMMA_DECIMAL)
            assertTrue("Current price confidence=${cost.confidenceRate}", cost.isDetected)
            assertEquals(4_200_000.0, cost.numberDetected!!, 0.001)
            for (area in listOf(Rect(704, 886, 707, 889), Rect(782, 890, 785, 893))) {
                assertTrue("Current green price must allow confirmation", detector.detectColor(-10034676, area, 7).isDetected)
            }
        }
    }

    @Test fun acceptsBothObservedGreenPricesAndRejectsOtherButtonColors() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            for ((color, available) in listOf(
                Color.rgb(89, 197, 10) to true,
                Color.rgb(115, 255, 13) to true,
                Color.rgb(119, 251, 20) to true,
                Color.RED to false,
                Color.WHITE to false,
                Color.BLACK to false,
                Color.rgb(208, 238, 132) to false,
            )) {
                bitmap.eraseColor(color)
                detector.setScreenBitmap(bitmap, "confirmation-color-$color")
                try {
                    assertEquals("Price color $color", available,
                        detector.detectColor(-10034676, Rect(0, 0, 20, 20), 7).isDetected)
                } finally { detector.releaseScreenBitmap(bitmap) }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun recognizesBrightRedPriceAndAvailableAlternativeResource() {
        withCrop("selection_prices_current", 1.0) {
            for (area in listOf(Rect(39, 18, 42, 21), Rect(105, 25, 108, 28))) {
                assertTrue("Insufficient gold must allow switching resource", detector.detectColor(-1934991, area, 10).isDetected)
                assertFalse("Insufficient gold must not allow payment", detector.detectColor(-1381654, area, 10).isDetected)
            }
            for (area in listOf(Rect(249, 18, 252, 21), Rect(315, 25, 318, 28))) {
                assertTrue("Available elixir must allow payment", detector.detectColor(-1381654, area, 10).isDetected)
                assertFalse("Available elixir must not match insufficient price", detector.detectColor(-1934991, area, 10).isDetected)
            }
        }
    }

    @Test fun recognizesBatchSelectionCostsAndControlsAcrossAffordableCounts() {
        val context = InstrumentationRegistry.getInstrumentation().context
        for (count in 1..7) withCrop("batch_selection_$count", 1.0) {
            for ((resource, x) in listOf("gold" to 0, "elixir" to 210)) {
                val cost = detector.detectNumber(Rect(590 + x, 65, 740 + x, 105), 80, NumberFormatType.COMMA_DECIMAL)
                assertTrue("$resource batch $count confidence=${cost.confidenceRate}", cost.isDetected)
                assertEquals("$resource batch $count", count * 4_200_000.0, cost.numberDetected!!, 0.001)
                val available = resource == "elixir" && count <= 2
                for (area in listOf(Rect(724 + x, 87, 727 + x, 90), Rect(646 + x, 82, 649 + x, 85))) {
                    assertTrue("$resource batch $count availability", detector.detectColor(if (available) -1381654 else -1934991, area, 10).isDetected)
                    assertFalse("$resource batch $count opposite availability", detector.detectColor(if (available) -1934991 else -1381654, area, 10).isDetected)
                }
            }
            for ((name, area) in listOf("wall-batch-remove" to Rect(18, 90, 55, 128),
                "wall-batch-add" to Rect(470, 80, 530, 165))) {
                val reference = context.assets.open("wall_calibration/$name.png").use(BitmapFactory::decodeStream)
                try {
                    assertTrue("$name batch $count", detector.detectImage(reference, area.width(), area.height(), area, 15).isDetected)
                } finally { reference.recycle() }
            }
            val level = context.assets.open("wall_calibration/level18.png").use(BitmapFactory::decodeStream)
            try {
                assertTrue("Batch $count is level 18", detector.detectImage(level, 72, 42, Rect(150, 0, 770, 58), 15).isDetected)
            } finally { level.recycle() }
        }
    }

    @Test fun recognizesBatchConfirmationAmountAndResourceWithoutConfusingCurrencies() {
        val context = InstrumentationRegistry.getInstrumentation().context
        for (resource in listOf("gold", "elixir")) withCrop("batch_confirmation_$resource", 1.0) {
            val cost = detector.detectNumber(Rect(470, 248, 670, 292), 80, NumberFormatType.COMMA_DECIMAL)
            assertTrue("Batch cost in $resource confidence=${cost.confidenceRate}", cost.isDetected)
            assertEquals(8_400_000.0, cost.numberDetected!!, 0.001)
            for (candidate in listOf("gold", "elixir")) {
                assertEquals("Batch $resource versus $candidate", candidate == resource,
                    detector.detectText(listOf(if (candidate == "gold") "Ouro" else "Elixir"), "latin", Rect(650, 248, 810, 292), 90).isDetected)
            }
            for ((name, area) in listOf("wall-batch-confirm" to Rect(220, 60, 740, 125),
                "wall-batch-ok" to Rect(640, 428, 730, 485))) {
                val reference = context.assets.open("wall_calibration/$name.png").use(BitmapFactory::decodeStream)
                try {
                    assertTrue(name, detector.detectImage(reference, area.width(), area.height(), area, 12).isDetected)
                } finally { reference.recycle() }
            }
        }
    }

    @Test fun recognizesSingleSelectionAfterBatchUpgradeCollapsesTheGroup() {
        val context = InstrumentationRegistry.getInstrumentation().context
        withCrop("batch_upgraded", 1.0) {
            for ((name, area, threshold) in listOf(
                Triple("title_reference", Rect(20, 0, 230, 65), 18),
                Triple("level19", Rect(0, 0, 550, 58), 15),
            )) {
                val reference = context.assets.open("wall_calibration/$name.png").use(BitmapFactory::decodeStream)
                try {
                    val width = if (name == "level19") 72 else 150
                    assertTrue(name, detector.detectImage(reference, width, 42, area, threshold).isDetected)
                } finally { reference.recycle() }
            }
        }
    }

    @Test fun recognizesShiftedBattleExitConfirmationWithinItsButton() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val oldReference = context.assets.open("wall_calibration/battle_exit_reference.png").use(BitmapFactory::decodeStream)
        val reference = context.assets.open("wall_calibration/battle_exit_current_reference.png").use(BitmapFactory::decodeStream)
        try {
            withCrop("battle_exit_confirmation", 1.0) { bitmap ->
                val exact = detector.detectImage(oldReference, 75, 50, Rect(20, 20, 95, 70), 12)
                assertFalse("Exact old position rejected the shifted OK glyph", exact.isDetected)
                val nearby = detector.detectImage(reference, 75, 50, Rect(0, 0, bitmap.width, bitmap.height), 12)
                assertTrue("Shifted OK glyph confidence=${nearby.confidenceRate}", nearby.isDetected)
            }
        } finally { reference.recycle(); oldReference.recycle() }
    }

    @Test fun recognizesCalibratedFreeBuilderWithoutAmbiguousOcr() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val reference = context.assets.open("wall_calibration/builder_1_reference.png").use(BitmapFactory::decodeStream)
        try {
            val crop = context.assets.open("wall_calibration/builders.png").use(BitmapFactory::decodeStream)
            val frame = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
            Canvas(frame).drawBitmap(crop, 2045f, 795f, null)
            for (scale in listOf(1.0, 1600.0 / 2340)) {
                val scaled = Bitmap.createScaledBitmap(frame, (2340 * scale).toInt(), (1080 * scale).toInt(), true)
                detector.setScreenBitmap(scaled, "builder-live-frame")
                val area = Rect((2055 * scale).toInt(), (815 * scale).toInt(), (2076 * scale).toInt(), (853 * scale).toInt())
                val result = detector.detectImage(reference, area.width(), area.height(), area, 15)
                assertTrue("Free builder at scale=$scale, confidence=${result.confidenceRate}", result.isDetected)
                val otherBuilderCounts = (2..6).map { "$it/6" } + (2..6).map { "${it}76]" }
                val otherBuilders = detector.detectText(
                    otherBuilderCounts, "latin",
                    Rect((2045 * scale).toInt(), (795 * scale).toInt(), (2140 * scale).toInt(), (860 * scale).toInt()),
                    80,
                )
                assertFalse("One free builder must not match another count at scale=$scale", otherBuilders.isDetected)
                detector.releaseScreenBitmap(scaled)
                if (scaled !== frame) scaled.recycle()
            }
            crop.recycle(); frame.recycle()
        } finally { reference.recycle() }
    }

    @Test fun findsEligibleWallAtCloseZoomAndRejectsMaxLevelWall() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val reference = context.assets.open("wall_calibration/wall_18_reference.png").use(BitmapFactory::decodeStream)
        try {
            val crop = context.assets.open("wall_calibration/candidates.png").use(BitmapFactory::decodeStream)
            val frame = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
            Canvas(frame).drawBitmap(crop, 1200f, 500f, null)
            detector.setScreenBitmap(frame, "close-wall-candidates")
            val found = detector.detectImage(reference, 40, 40, Rect(300, 80, 1950, 960), 10)
            assertTrue("Eligible wall confidence=${found.confidenceRate}", found.isDetected)
            detector.releaseScreenBitmap(frame)
            crop.recycle(); frame.recycle()
            withCrop("wall_19_area", 1.0) { bitmap ->
                val maxLevel = detector.detectImage(reference, 40, 40, Rect(0, 0, bitmap.width, bitmap.height), 10)
                assertFalse("Max level wall must not be selected, confidence=${maxLevel.confidenceRate}", maxLevel.isDetected)
            }
        } finally { reference.recycle() }
    }

    @Test fun recognizesSelectionInWholeFrameAtEngineScale() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val crop = context.assets.open("wall_calibration/selection.png").use(BitmapFactory::decodeStream)
        val frame = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        Canvas(frame).drawBitmap(crop, 970f, 690f, null)
        for (scale in listOf(1.0, 1600.0 / 2340)) {
            val scaled = Bitmap.createScaledBitmap(frame, (2340 * scale).roundToInt(), (1080 * scale).roundToInt(), true)
            detector.setScreenBitmap(scaled, "selection-live-frame")
            for ((name, source, threshold) in listOf(Triple("title_reference", Rect(970, 700, 1120, 742), 18), Triple("level18", Rect(1290, 700, 1362, 742), 15))) {
                val reference = context.assets.open("wall_calibration/$name.png").use(BitmapFactory::decodeStream)
                val x = (source.left * scale).roundToInt()
                val y = (source.top * scale).roundToInt()
                val width = (source.width() * scale).roundToInt()
                val height = (source.height() * scale).roundToInt()
                val result = detector.detectImage(reference, width, height, Rect(x - 1, y - 1, x + width + 1, y + height + 1), threshold)
                assertTrue("$name at scale=$scale, confidence=${result.confidenceRate}", result.isDetected)
                reference.recycle()
            }
            detector.releaseScreenBitmap(scaled)
            if (scaled !== frame) scaled.recycle()
        }
        crop.recycle(); frame.recycle()
    }

    @Test fun recognizesCurrentGoldInTightAreaAtProfileResolution() {
        withGoldFrame(scale = 1.0) { area ->
            val result = detector.detectNumber(area, 80)
            assertTrue("Gold detected at native resolution", result.isDetected)
            assertEquals(13_173_842.0, result.numberDetected!!, 0.001)
        }
    }

    @Test fun doesNotTreatPositiveOpponentLootAsEmpty() {
        // Only zero is meaningful for this rule; partial nonzero OCR must continue the attack.
        for (name in listOf("loot_gold", "loot_elixir")) {
            withCrop(name, 1.0) { bitmap ->
                val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80, NumberFormatType.COMMA_DECIMAL)
                assertTrue("$name detected with confidence ${result.confidenceRate}", result.isDetected)
                assertTrue("$name still has loot: ${result.numberDetected}", result.numberDetected!! > 0.0)
            }
        }
    }

    @Test fun rejectsPartialGoldReadingAtReducedQuality() {
        // This real frame is recognized as a malformed number at 1600. Parsing its prefix
        // must not signal a low balance and resume farming while gold can still pay a wall.
        withGoldFrame(scale = 1600.0 / 2340) { area ->
            val result = detector.detectNumber(area, 80)
            assertFalse("Partial number must not count as a valid balance: ${result.numberDetected}", result.isDetected)
        }
    }

    private fun withGoldFrame(scale: Double, test: (Rect) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val crop = context.assets.open("wall_calibration/gold_current.png").use(BitmapFactory::decodeStream)
        val frame = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        Canvas(frame).drawBitmap(crop, 1970f, 32f, null)
        val scaled = Bitmap.createScaledBitmap(frame, (2340 * scale).roundToInt(), (1080 * scale).roundToInt(), true)
        detector.setScreenBitmap(scaled, "gold-live-frame")
        val x = (2005 * scale).roundToInt()
        val y = (37 * scale).roundToInt()
        try {
            test(Rect(x, y, x + (170 * scale).roundToInt(), y + (38 * scale).roundToInt()))
        } finally {
            detector.releaseScreenBitmap(scaled)
            if (scaled !== frame) scaled.recycle()
            crop.recycle(); frame.recycle()
        }
    }

    @Test fun distinguishesAffordableAndInsufficientPricesWithoutResourceOcr() {
        val points = listOf(Rect(29, 6, 32, 9), Rect(95, 13, 98, 16))
        for ((name, expected, opposite) in listOf(Triple("cost", -1381654, -3839646), Triple("red_cost", -3839646, -1381654))) {
            withCrop(name, 1.0) {
                points.forEach { area ->
                    assertTrue("$name expected color", detector.detectColor(expected, area, 10).isDetected)
                    assertFalse("$name opposite color", detector.detectColor(opposite, area, 10).isDetected)
                }
            }
        }
        withCrop("final_cost", 1.0) {
            for (area in listOf(Rect(49, 4, 52, 7), Rect(127, 8, 130, 11))) {
                assertTrue(detector.detectColor(-10034676, area, 7).isDetected)
                assertFalse(detector.detectColor(-3839646, area, 6).isDetected)
            }
        }
    }

    @Test fun recognizesAvailableGrayPriceOnTheCurrentVillage() {
        withCrop("cost_gray", 1.0) {
            for (area in listOf(Rect(29, 6, 32, 9), Rect(95, 13, 98, 16))) {
                assertTrue("Current affordable glyph must be accepted", detector.detectColor(-1381654, area, 10).isDetected)
                assertFalse("Current affordable glyph must not be red", detector.detectColor(-3839646, area, 10).isDetected)
            }
        }
    }

    @Test fun recognizesFullResourceAmountsWithPortugueseNumberFormat() {
        for ((name, expected) in listOf("gold_wide" to 13_266_140.0, "gold_wide_current" to 13_264_440.0,
            "elixir_wide" to 3_400_642.0, "gold_current" to 13_173_842.0)) {
            withCrop(name, 1.0) { bitmap ->
                val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80, NumberFormatType.COMMA_DECIMAL)
                assertTrue("$name should be readable, confidence=${result.confidenceRate}", result.isDetected)
                assertEquals(name, expected, result.numberDetected!!, 0.001)
            }
        }
    }

    @Test fun recognizesDestructionNumberWithPercentSuffix() {
        withCrop("destruction_zero", 1.0) { bitmap ->
            val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80)
            assertTrue("Destruction 0% should be readable, confidence=${result.confidenceRate}", result.isDetected)
            assertEquals(0.0, result.numberDetected!!, 0.001)
        }
        val bitmap = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("75%", 20f, 80f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 64f
                typeface = Typeface.MONOSPACE
            })
        }
        detector.setScreenBitmap(bitmap, "percentage-value")
        try {
            val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 80)
            assertTrue(result.isDetected)
            assertEquals(75.0, result.numberDetected!!, 0.001)
        } finally {
            detector.releaseScreenBitmap(bitmap)
            bitmap.recycle()
        }
    }

    @Test fun recognizesResourceAndCostsAtBothEngineScales() {
        for ((name, expected) in listOf("gold" to 17_373_842.0, "cost" to 4_200_000.0, "final_cost" to 4_200_000.0, "red_cost" to 4_200_000.0)) {
            for (scale in listOf(1.0, 1600.0 / 2340)) withCrop(name, scale) { bitmap ->
                val result = detector.detectNumber(Rect(0, 0, bitmap.width, bitmap.height), 2)
                assertTrue("$name at scale=$scale, confidence=${result.confidenceRate}, value=${result.numberDetected}", result.isDetected)
                assertEquals("$name at scale=$scale", expected, result.numberDetected!!, 0.001)
            }
        }
    }

    @Test fun distinguishesEligibleAndUpgradedLevels() {
        val context = InstrumentationRegistry.getInstrumentation().context
        for (level in listOf(18, 19)) {
            val reference = context.assets.open("wall_calibration/level$level.png").use(BitmapFactory::decodeStream)
            try {
                for (actual in listOf(18, 19)) for (scale in listOf(1.0, 1600.0 / 2340)) withCrop("level$actual", scale) { bitmap ->
                    val result = detector.detectImage(reference, bitmap.width, bitmap.height, Rect(0, 0, bitmap.width, bitmap.height), 15)
                    if (actual == level) assertTrue("Expected level $level at scale=$scale", result.isDetected)
                    else assertFalse("Level $actual must not confirm level $level at scale=$scale", result.isDetected)
                }
            } finally { reference.recycle() }
        }
    }

    private fun withCrop(name: String, scale: Double, test: (Bitmap) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val original = context.assets.open("wall_calibration/$name.png").use(BitmapFactory::decodeStream)
        val bitmap = Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
        detector.setScreenBitmap(bitmap, "wall-calibration-$name")
        try { test(bitmap) } finally { detector.releaseScreenBitmap(bitmap); bitmap.recycle(); if (bitmap !== original) original.recycle() }
    }
}
