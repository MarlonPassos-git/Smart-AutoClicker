/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.domain

import android.graphics.Point
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.core.bitmaps.CONDITION_FILE_PREFIX
import com.buzbuz.smartautoclicker.core.domain.model.IN_AREA
import com.buzbuz.smartautoclicker.core.domain.model.condition.IMAGE_REFERENCES_LIMIT
import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.feature.smart.config.data.ScenarioEditor

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ImageReferenceEditorTest {

    private val screenSize = Point(300, 500)

    @Test
    fun `add appends reference`() {
        val updated = ImageReferenceEditor.add(condition(), reference(2), screenSize)

        assertEquals(listOf("reference-1.png", "reference-2.png"), updated.references.map { it.path })
    }

    @Test
    fun `add keeps condition unchanged at limit`() {
        val condition = condition(List(IMAGE_REFERENCES_LIMIT) { reference(it) })

        assertSame(condition, ImageReferenceEditor.add(condition, reference(21), screenSize))
    }

    @Test
    fun `replace preserves index`() {
        val updated = ImageReferenceEditor.replace(
            condition(listOf(reference(1), reference(2))),
            index = 0,
            reference = reference(3),
            screenSize = screenSize,
        )

        assertEquals(listOf("reference-3.png", "reference-2.png"), updated.references.map { it.path })
    }

    @Test
    fun `remove refuses last reference`() {
        val condition = condition()

        assertSame(condition, ImageReferenceEditor.remove(condition, index = 0))
    }

    @Test
    fun `remove deletes selected reference`() {
        val updated = ImageReferenceEditor.remove(
            condition(listOf(reference(1), reference(2))),
            index = 0,
        )

        assertEquals(listOf("reference-2.png"), updated.references.map { it.path })
    }

    @Test
    fun `move changes ordered priority`() {
        val updated = ImageReferenceEditor.move(
            condition(listOf(reference(1), reference(2), reference(3))),
            from = 2,
            to = 0,
        )

        assertEquals(listOf(3, 1, 2), updated.references.map { it.area.left })
    }

    @Test
    fun `reorder accepts ordered references with same size`() {
        val condition = condition(listOf(reference(1), reference(2)))
        val updated = ImageReferenceEditor.reorder(condition, condition.references.reversed())

        assertEquals(listOf("reference-2.png", "reference-1.png"), updated.references.map { it.path })
    }

    @Test
    fun `in area expands and shifts inside screen for larger reference`() {
        val condition = condition().copy(detectionArea = Rect(250, 10, 290, 50))
        val largeReference = ImageReference("large.png", Rect(0, 0, 100, 80))
        val updated = ImageReferenceEditor.add(condition, largeReference, screenSize)

        assertEquals(Rect(200, 0, 300, 80), updated.detectionArea)
    }

    @Test
    fun `removal never shrinks expanded in area`() {
        val expanded = condition(listOf(reference(1), reference(2))).copy(
            detectionArea = Rect(20, 30, 180, 190),
        )
        val updated = ImageReferenceEditor.remove(expanded, index = 1)

        assertEquals(Rect(20, 30, 180, 190), updated.detectionArea)
    }

    @Test
    fun `fixed position preserves image size at screen edge`() {
        val positioned = ImageReferenceEditor.positionWithFixedSize(
            position = Rect(290, 490, 300, 500),
            size = Rect(0, 0, 80, 60),
            screenSize = screenSize,
        )

        assertEquals(Rect(220, 440, 300, 500), positioned)
    }

    @Test
    fun `create reference stores png path and tracks it for cleanup`() = runTest {
        val bitmapRepository = mock(BitmapRepository::class.java)
        val bitmap = mock(Bitmap::class.java)
        val builder = EditedItemsBuilder(bitmapRepository, mock(ScenarioEditor::class.java))
        `when`(bitmapRepository.saveImageConditionBitmap(bitmap, CONDITION_FILE_PREFIX))
            .thenReturn("stored.png")

        val reference = builder.createImageReference(Rect(1, 2, 31, 42), bitmap)

        assertEquals(ImageReference("stored.png", Rect(1, 2, 31, 42)), reference)
        assertEquals(listOf("stored.png"), builder.newImageConditionsPaths)
        verify(bitmapRepository).saveImageConditionBitmap(bitmap, CONDITION_FILE_PREFIX)
    }

    private fun condition(
        references: List<ImageReference> = listOf(reference(1)),
    ) = ScreenCondition.Image(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Image condition",
        threshold = 10,
        shouldBeDetected = true,
        priority = 0,
        references = references,
        detectionType = IN_AREA,
        detectionArea = Rect(10, 10, 60, 60),
    )

    private fun reference(index: Int) = ImageReference(
        path = "reference-$index.png",
        area = Rect(index, index, index + 20, index + 20),
    )
}
