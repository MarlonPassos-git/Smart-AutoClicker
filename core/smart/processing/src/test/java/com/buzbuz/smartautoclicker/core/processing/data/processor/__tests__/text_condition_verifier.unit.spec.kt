/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.processing.data.processor

import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScreenConditionScalingInfo
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class TextConditionVerifierTest {

    @Test
    fun `all text alternatives are sent in one detector call`() = runTest {
        val condition = textCondition()
        val detector = mock(ImageDetector::class.java)
        val scaling = mock(ScalingManager::class.java)
        mockDetection(condition, detector, scaling)
        val verifier = ConditionsVerifier(processingState(), detector, scaling, { _, _, _ -> null })

        verifier.verifyConditions(AND, listOf(condition))

        verify(detector).detectText(condition.texts, OCRAlphabet.LATIN.name, condition.detectionArea, 80)
    }

    private fun mockDetection(
        condition: ScreenCondition.Text,
        detector: ImageDetector,
        scaling: ScalingManager,
    ) {
        `when`(scaling.getScreenConditionScalingInfo(condition)).thenReturn(
            ScreenConditionScalingInfo.Text(condition, condition.detectionArea)
        )
        `when`(scaling.scaleUpDetectionResult(any())).doAnswer { it.getArgument<Point>(0) }
        `when`(
            detector.detectText(condition.texts, OCRAlphabet.LATIN.name, condition.detectionArea, 80)
        ).thenReturn(DetectionResult(isDetected = true))
    }

    private fun processingState() = ProcessingState(
        screenEvents = emptyList(),
        triggerEvents = emptyList(),
        counters = emptyList(),
        progressListener = null,
    )

    private fun textCondition() = ScreenCondition.Text(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Names",
        threshold = 80,
        shouldBeDetected = true,
        priority = 0,
        texts = listOf("Marlon", "Ana", "Júlia"),
        detectionArea = Rect(0, 0, 100, 100),
        alphabet = OCRAlphabet.LATIN,
    )
}
