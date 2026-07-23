/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.processing.data.processor

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ImageReferenceScalingInfo
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScreenConditionScalingInfo

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ImageReferenceVerifierTest {

    private val firstBitmap = mock(Bitmap::class.java)
    private val secondBitmap = mock(Bitmap::class.java)
    private val detector = mock(ImageDetector::class.java)
    private val scalingManager = mock(ScalingManager::class.java)

    @Test
    fun `first detected reference stops sequence`() = runTest {
        val condition = imageCondition(shouldBeDetected = true)
        val scaling = mockScaling(condition)
        mockDetection(firstBitmap, scaling.references[0], DetectionResult(true, position = Point(11, 12)))

        val result = verify(condition, mapOf("first.png" to firstBitmap, "second.png" to secondBitmap))

        assertTrue(result.isFulfilled)
        assertEquals(Point(11, 12), result.position)
        verify(detector, never()).detectImage(eq(secondBitmap), any(), any(), any(), any())
    }

    @Test
    fun `all misses fulfill absence and preserve best confidence`() = runTest {
        val condition = imageCondition(shouldBeDetected = false)
        val scaling = mockScaling(condition)
        mockDetection(firstBitmap, scaling.references[0], DetectionResult(false, 0.35, Point(1, 2)))
        mockDetection(secondBitmap, scaling.references[1], DetectionResult(false, 0.82, Point(9, 10)))

        val result = verify(condition, mapOf("first.png" to firstBitmap, "second.png" to secondBitmap))

        assertTrue(result.isFulfilled)
        assertFalse(result.haveBeenDetected)
        assertEquals(0.82, result.confidenceRate, 0.0)
        assertEquals(Point(9, 10), result.position)
    }

    @Test
    fun `all misses fail presence`() = runTest {
        val condition = imageCondition(shouldBeDetected = true)
        val scaling = mockScaling(condition)
        mockDetection(firstBitmap, scaling.references[0], DetectionResult(false))
        mockDetection(secondBitmap, scaling.references[1], DetectionResult(false))

        val result = verify(condition, mapOf("first.png" to firstBitmap, "second.png" to secondBitmap))

        assertFalse(result.isFulfilled)
    }

    @Test
    fun `missing bitmap does not block later presence match`() = runTest {
        val condition = imageCondition(shouldBeDetected = true)
        val scaling = mockScaling(condition)
        mockDetection(secondBitmap, scaling.references[1], DetectionResult(true, position = Point(40, 50)))

        val result = verify(condition, mapOf("second.png" to secondBitmap))

        assertTrue(result.isFulfilled)
        assertEquals(Point(40, 50), result.position)
    }

    @Test
    fun `missing bitmap invalidates absence after remaining misses`() = runTest {
        val condition = imageCondition(shouldBeDetected = false)
        val scaling = mockScaling(condition)
        mockDetection(secondBitmap, scaling.references[1], DetectionResult(false, confidenceRate = 0.7))

        val result = verify(condition, mapOf("second.png" to secondBitmap))

        assertFalse(result.isFulfilled)
        assertEquals(0.7, result.confidenceRate, 0.0)
    }

    @Test
    fun `each reference uses its scaled size and area`() = runTest {
        val condition = imageCondition(shouldBeDetected = false)
        val scaling = mockScaling(condition)
        mockDetection(firstBitmap, scaling.references[0], DetectionResult(false))
        mockDetection(secondBitmap, scaling.references[1], DetectionResult(false))

        verify(condition, mapOf("first.png" to firstBitmap, "second.png" to secondBitmap))

        verify(detector).detectImage(firstBitmap, 30, 40, Rect(0, 0, 90, 100), 10)
        verify(detector).detectImage(secondBitmap, 50, 60, Rect(100, 110, 250, 300), 10)
    }

    @Test
    fun `empty reference list never fulfills absence`() = runTest {
        val condition = imageCondition(shouldBeDetected = false).copy(references = emptyList())
        `when`(scalingManager.getScreenConditionScalingInfo(condition)).thenReturn(
            ScreenConditionScalingInfo.Image(condition, emptyList()),
        )

        val result = verify(condition, emptyMap())

        assertFalse(result.isFulfilled)
    }

    private suspend fun verify(
        condition: ScreenCondition.Image,
        bitmaps: Map<String, Bitmap>,
    ) = ConditionsVerifier(
        state = processingState(),
        imageDetector = detector,
        scalingManager = scalingManager,
        bitmapSupplier = { path, _, _ -> bitmaps[path] },
    ).verifyConditions(AND, listOf(condition)).getScreenConditionResult(condition.id.databaseId)!!

    private fun mockScaling(condition: ScreenCondition.Image): ScreenConditionScalingInfo.Image {
        val scaling = ScreenConditionScalingInfo.Image(
            condition,
            listOf(
                ImageReferenceScalingInfo(condition.references[0], Rect(0, 0, 90, 100), Rect(10, 20, 40, 60)),
                ImageReferenceScalingInfo(condition.references[1], Rect(100, 110, 250, 300), Rect(5, 6, 55, 66)),
            ),
        )
        `when`(scalingManager.getScreenConditionScalingInfo(condition)).thenReturn(scaling)
        `when`(scalingManager.scaleUpDetectionResult(any())).doAnswer { it.getArgument<Point>(0) }
        return scaling
    }

    private fun mockDetection(
        bitmap: Bitmap,
        reference: ImageReferenceScalingInfo,
        result: DetectionResult,
    ) {
        `when`(
            detector.detectImage(
                bitmap,
                reference.imageArea.width(),
                reference.imageArea.height(),
                reference.detectionArea,
                10,
            ),
        ).thenReturn(result)
    }

    private fun imageCondition(shouldBeDetected: Boolean) = ScreenCondition.Image(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Image condition",
        threshold = 10,
        shouldBeDetected = shouldBeDetected,
        priority = 0,
        references = listOf(
            ImageReference("first.png", Rect(10, 20, 40, 60)),
            ImageReference("second.png", Rect(5, 6, 55, 66)),
        ),
        detectionType = EXACT,
    )

    private fun processingState() = ProcessingState(
        screenEvents = emptyList(),
        triggerEvents = emptyList(),
        counters = emptyList(),
        progressListener = null,
    )
}
