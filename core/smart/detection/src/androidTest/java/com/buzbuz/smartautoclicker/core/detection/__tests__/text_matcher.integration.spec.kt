/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.detection

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.data.TestImage
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import com.buzbuz.smartautoclicker.core.detection.utils.loadTestBitmap
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class TextMatcherListTest {

    private lateinit var detector: ImageDetector
    private lateinit var screenBitmap: Bitmap

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        detector = NativeDetector.newInstance()
            ?: throw IllegalStateException("Native detector instance expected, received null")
        detector.init()
        loadModels(context)
        screenBitmap = context.loadTestBitmap(TestImage.NumberConditionsScreen)
        detector.setScreenBitmap(screenBitmap, "text-list-test")
    }

    @After
    fun tearDown() {
        detector.close()
    }

    @Test
    fun detectsTextWhenSecondAlternativeMatches() {
        val numberCase = TestImage.NumberConditionsScreen.numberTestCases.first()

        val result = detector.detectText(
            conditionTexts = listOf("Missing", "42"),
            recognitionModelId = "latin",
            detectionArea = numberCase.detectionArea,
            threshold = 80,
        )

        assertTrue(result.isDetected)
    }

    @Test
    fun doesNotDetectWhenEveryAlternativeIsAbsent() {
        val numberCase = TestImage.NumberConditionsScreen.numberTestCases.first()

        val result = detector.detectText(
            conditionTexts = listOf("Marlon", "Ana"),
            recognitionModelId = "latin",
            detectionArea = numberCase.detectionArea,
            threshold = 80,
        )

        assertFalse(result.isDetected)
    }

    private fun loadModels(context: Context) {
        val (detectionPath, recognitionModels) = context.extractTestOcrModels()
        val loaded = detector.loadTextDetectionModels(detectionPath, recognitionModels)
        if (!loaded) throw IllegalStateException("OCR model load expected true, received false")
    }
}
