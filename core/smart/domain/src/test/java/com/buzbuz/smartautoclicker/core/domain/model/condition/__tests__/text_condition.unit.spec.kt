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
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.database.entity.ConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ConditionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class TextConditionTest {

    @Test
    fun `single text remains default valid configuration`() {
        assertTrue(textCondition(listOf("Marlon")).isComplete())
    }

    @Test
    fun `ten non blank texts are accepted`() {
        assertTrue(textCondition(List(TEXT_CONDITION_VALUES_LIMIT) { "Text $it" }).isComplete())
    }

    @Test
    fun `more than ten texts cannot be saved`() {
        assertFalse(textCondition(List(TEXT_CONDITION_VALUES_LIMIT + 1) { "Text $it" }).isComplete())
    }

    @Test
    fun `blank alternative cannot be saved`() {
        assertFalse(textCondition(listOf("Marlon", " ")).isComplete())
    }

    @Test
    fun `text alternatives survive entity round trip`() {
        val original = textCondition(listOf("Marlon", "Ana", "Júlia: teste"))

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `legacy text entity becomes single text condition`() {
        val entity = textEntity("Marlon")

        assertEquals(listOf("Marlon"), (entity.toDomain() as ScreenCondition.Text).texts)
    }

    private fun textCondition(texts: List<String>) = ScreenCondition.Text(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Names",
        threshold = 80,
        shouldBeDetected = true,
        priority = 0,
        texts = texts,
        detectionArea = Rect(0, 0, 100, 100),
        alphabet = OCRAlphabet.LATIN,
    )

    private fun textEntity(text: String) = ConditionEntity(
        id = 1L,
        eventId = 2L,
        name = "Names",
        type = ConditionType.ON_TEXT_DETECTED,
        priority = 0,
        threshold = 80,
        textToDetect = text,
        detectionAreaLeft = 0,
        detectionAreaTop = 0,
        detectionAreaRight = 100,
        detectionAreaBottom = 100,
    )
}
