/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.domain.model.condition

import android.graphics.Rect
import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.database.entity.CompleteConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ConditionType
import com.buzbuz.smartautoclicker.core.database.entity.ImageReferenceEntity
import com.buzbuz.smartautoclicker.core.domain.model.EXACT

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ImageConditionTest {

    @Test
    fun `one reference is valid`() {
        assertTrue(imageCondition(listOf(reference(1))).isComplete())
    }

    @Test
    fun `twenty references are valid`() {
        assertTrue(imageCondition(List(IMAGE_REFERENCES_LIMIT, ::reference)).isComplete())
    }

    @Test
    fun `zero references are invalid`() {
        assertFalse(imageCondition(emptyList()).isComplete())
    }

    @Test
    fun `twenty one references are invalid`() {
        assertFalse(imageCondition(List(IMAGE_REFERENCES_LIMIT + 1, ::reference)).isComplete())
    }

    @Test
    fun `duplicate references are valid`() {
        val duplicate = reference(1)

        assertTrue(imageCondition(listOf(duplicate, duplicate)).isComplete())
    }

    @Test
    fun `reference order changes condition hash and survives copy`() {
        val references = listOf(reference(1), reference(2))
        val condition = imageCondition(references)
        val reordered = condition.copy(references = references.reversed())

        assertNotEquals(condition.hashCodeNoIds(), reordered.hashCodeNoIds())
        assertEquals(references.reversed(), reordered.references)
    }

    @Test
    fun `first reference is mirrored into legacy entity`() {
        val condition = imageCondition(listOf(reference(2), reference(1)))
        val entity = condition.toEntity()

        assertEquals("reference-2.png", entity.path)
        assertEquals(2, entity.areaLeft)
        assertEquals(22, entity.areaRight)
    }

    @Test
    fun `related references prevail and are ordered by priority`() {
        val completeEntity = CompleteConditionEntity(
            condition = legacyEntity(),
            imageReferences = listOf(referenceEntity(priority = 1), referenceEntity(priority = 0)),
        )

        val condition = completeEntity.toDomain() as ScreenCondition.Image

        assertEquals(listOf("related-0.png", "related-1.png"), condition.references.map { it.path })
    }

    @Test
    fun `legacy entity becomes one reference`() {
        val condition = legacyEntity().toDomain() as ScreenCondition.Image

        assertEquals(listOf(ImageReference("legacy.png", Rect(1, 2, 31, 42))), condition.references)
    }

    @Test
    fun `reference entities preserve duplicates and priority`() {
        val duplicate = reference(4)
        val entities = imageCondition(listOf(duplicate, duplicate)).toReferenceEntities(conditionId = 7L)

        assertEquals(listOf(0, 1), entities.map { it.priority })
        assertEquals(listOf("reference-4.png", "reference-4.png"), entities.map { it.path })
    }

    private fun imageCondition(references: List<ImageReference>) = ScreenCondition.Image(
        id = Identifier(databaseId = 7L),
        eventId = Identifier(databaseId = 8L),
        name = "Image condition",
        threshold = 10,
        shouldBeDetected = true,
        priority = 0,
        references = references,
        detectionType = EXACT,
    )

    private fun reference(index: Int) = ImageReference(
        path = "reference-$index.png",
        area = Rect(index, index + 1, index + 20, index + 21),
    )

    private fun legacyEntity() = ConditionEntity(
        id = 7L,
        eventId = 8L,
        name = "Legacy image",
        type = ConditionType.ON_IMAGE_DETECTED,
        priority = 0,
        shouldBeDetected = true,
        path = "legacy.png",
        areaLeft = 1,
        areaTop = 2,
        areaRight = 31,
        areaBottom = 42,
        threshold = 10,
        detectionType = EXACT,
    )

    private fun referenceEntity(priority: Int) = ImageReferenceEntity(
        conditionId = 7L,
        priority = priority,
        path = "related-$priority.png",
        areaLeft = priority,
        areaTop = priority,
        areaRight = priority + 10,
        areaBottom = priority + 10,
    )
}
