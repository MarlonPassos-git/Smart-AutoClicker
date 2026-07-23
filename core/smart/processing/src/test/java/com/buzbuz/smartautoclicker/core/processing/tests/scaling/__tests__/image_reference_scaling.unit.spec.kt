/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.processing.tests.scaling.__tests__

import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.IN_AREA
import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScreenConditionScalingInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ImageReferenceScalingUnitSpec {

    @Mock private lateinit var displayConfigManager: DisplayConfigManager
    private lateinit var scalingManager: ScalingManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(displayConfigManager.displayConfig).thenReturn(testDisplayConfig())
        scalingManager = ScalingManager(displayConfigManager)
    }

    @Test
    fun `EXACT keeps each reference image and detection area`() {
        val references = testReferences()
        val condition = testCondition(EXACT, references)

        scalingManager.startScaling(10_000.0, listOf(testEvent(condition)))

        val scalingInfo = scalingManager.getScreenConditionScalingInfo(condition)
                as ScreenConditionScalingInfo.Image
        assertEquals(references.map(ImageReference::area), scalingInfo.references.map { it.imageArea })
        assertEquals(listOf(Rect(9, 19, 31, 41), Rect(99, 109, 151, 171)),
            scalingInfo.references.map { it.detectionArea })
    }

    @Test
    fun `IN_AREA shares detection area and keeps each image size`() {
        val references = testReferences()
        val condition = testCondition(IN_AREA, references)

        scalingManager.startScaling(10_000.0, listOf(testEvent(condition)))

        val scalingInfo = scalingManager.getScreenConditionScalingInfo(condition)
                as ScreenConditionScalingInfo.Image
        assertEquals(references.map(ImageReference::area), scalingInfo.references.map { it.imageArea })
        assertEquals(listOf(Rect(4, 4, 301, 301), Rect(4, 4, 301, 301)),
            scalingInfo.references.map { it.detectionArea })
    }

    private fun testReferences(): List<ImageReference> = listOf(
        ImageReference("first.png", Rect(10, 20, 30, 40)),
        ImageReference("second.png", Rect(100, 110, 150, 170)),
    )

    private fun testCondition(
        detectionType: Int,
        references: List<ImageReference>,
    ): ScreenCondition.Image = ScreenCondition.Image(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Condition",
        threshold = 80,
        shouldBeDetected = true,
        priority = 0,
        references = references,
        detectionType = detectionType,
        detectionArea = if (detectionType == IN_AREA) Rect(5, 5, 300, 300) else null,
    )

    private fun testEvent(condition: ScreenCondition.Image): ScreenEvent = ScreenEvent(
        id = Identifier(databaseId = 2L),
        scenarioId = Identifier(databaseId = 3L),
        name = "Event",
        conditionOperator = 0,
        conditions = listOf(condition),
        actions = emptyList(),
        enabledOnStart = true,
        priority = 0,
        keepDetecting = false,
        cooldownMs = 0,
    )

    private fun testDisplayConfig(): DisplayConfig = DisplayConfig(
        sizePx = Point(1080, 1920),
        orientation = Configuration.ORIENTATION_PORTRAIT,
        safeInsetTopPx = 0,
        roundedCorners = emptyMap(),
    )
}
