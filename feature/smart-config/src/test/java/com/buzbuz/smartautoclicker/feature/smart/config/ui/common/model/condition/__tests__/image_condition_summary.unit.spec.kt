/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.common.model.condition

import android.content.Context
import android.graphics.Rect
import android.os.Build

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ImageConditionSummaryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `single reference hides additional count`() {
        val summary = condition(referenceCount = 1).toUiScreenCondition(context, false, false)

        assertNull(summary.additionalReferencesText)
        assertNull(summary.additionalReferencesContentDescription)
    }

    @Test
    fun `multiple references show count after first preview`() {
        assertEquals(
            "+1",
            condition(referenceCount = 2).toUiScreenCondition(context, false, false).additionalReferencesText,
        )
        assertEquals(
            "+19",
            condition(referenceCount = 20).toUiScreenCondition(context, false, false).additionalReferencesText,
        )
        assertEquals(
            "Total reference images: 20",
            condition(referenceCount = 20).toUiScreenCondition(context, false, false)
                .additionalReferencesContentDescription,
        )
    }

    private fun condition(referenceCount: Int) = ScreenCondition.Image(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Image condition",
        threshold = 10,
        shouldBeDetected = true,
        priority = 0,
        references = List(referenceCount) { index ->
            ImageReference("reference-$index.png", Rect(0, 0, 20, 20))
        },
        detectionType = EXACT,
    )
}
