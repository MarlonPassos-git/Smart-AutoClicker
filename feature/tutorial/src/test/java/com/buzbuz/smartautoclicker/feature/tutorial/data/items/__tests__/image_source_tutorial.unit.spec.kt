/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.tutorial.data.items

import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.data.Tutorial
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.data.step.TutorialStep
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.data.step.TutorialStepEndCondition
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.data.step.TutorialStepStartCondition
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredOverlayType
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredViewType
import com.buzbuz.smartautoclicker.feature.tutorial.data.items.root.basics.screenconditions.image.ImageConditionsMovingTargetTutorial
import com.buzbuz.smartautoclicker.feature.tutorial.data.items.root.basics.screenconditions.image.ImageConditionsStillTargetTutorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSourceTutorialTest {

    @Test
    fun `image tutorials choose screen capture before capture menu`() {
        listOf(
            ImageConditionsStillTargetTutorial.getTutorial(),
            ImageConditionsMovingTargetTutorial.getTutorial(),
        ).forEach(::assertCaptureSourcePrecedesCapture)
    }

    private fun assertCaptureSourcePrecedesCapture(tutorial: Tutorial) {
        val sourceIndex = tutorial.steps.indexOfFirst(::isCaptureSourceStep)
        assertTrue("Missing capture source step for ${tutorial.info.id}", sourceIndex >= 0)

        val sourceStep = tutorial.steps[sourceIndex] as TutorialStep.TutorialOverlay
        val captureStep = tutorial.steps[sourceIndex + 1] as TutorialStep.TutorialOverlay
        assertEquals(
            TutorialStepStartCondition.MonitoredOverlayDisplayed(MonitoredOverlayType.IMAGE_SOURCE_SELECTION),
            sourceStep.stepStartCondition,
        )
        assertEquals(
            TutorialStepStartCondition.MonitoredOverlayDisplayed(MonitoredOverlayType.CAPTURE_MENU),
            captureStep.stepStartCondition,
        )
    }

    private fun isCaptureSourceStep(step: TutorialStep): Boolean =
        step is TutorialStep.TutorialOverlay &&
                step.stepEndCondition == TutorialStepEndCondition.MonitoredViewClicked(
                    MonitoredViewType.IMAGE_SOURCE_SELECTION_CAPTURE,
                )
}
