/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text

import android.content.Context
import android.graphics.Rect
import android.os.Build
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.condition.TEXT_CONDITION_VALUES_LIMIT
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.EditedElementState
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.IEditionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class TextConditionViewModelTest {

    private val editionState: IEditionState = mockk(relaxed = true)
    private val editionRepository: EditionRepository = mockk(relaxed = true)
    private lateinit var condition: ScreenCondition.Text
    private lateinit var viewModel: TextConditionViewModel

    @Before
    fun setUp() {
        condition = textCondition(listOf("Marlon"))
        every { editionRepository.editionState } returns editionState
        every { editionRepository.isEditingCondition } returns flowOf(true)
        every { editionState.editedScreenConditionState } returns flowOf(
            EditedElementState(condition, hasChanged = false, canBeSaved = true)
        )
        every { editionState.getEditedCondition<ScreenCondition.Text>() } answers { condition }
        viewModel = TextConditionViewModel(mockk<Context>(relaxed = true), editionRepository, mockk<MonitoredViewsManager>())
    }

    @Test
    fun `add appends empty editable value`() {
        viewModel.addTextToDetect()

        verify { editionRepository.updateEditedCondition(condition.copy(texts = listOf("Marlon", ""))) }
    }

    @Test
    fun `add does nothing at ten values`() {
        condition = textCondition(List(TEXT_CONDITION_VALUES_LIMIT) { "Text $it" })

        viewModel.addTextToDetect()

        verify(exactly = 0) { editionRepository.updateEditedCondition(any()) }
    }

    @Test
    fun `remove deletes selected alternative`() {
        condition = textCondition(listOf("Marlon", "Ana", "Júlia"))

        viewModel.removeTextToDetect(1)

        verify { editionRepository.updateEditedCondition(condition.copy(texts = listOf("Marlon", "Júlia"))) }
    }

    @Test
    fun `remove keeps at least one value`() {
        viewModel.removeTextToDetect(0)

        verify(exactly = 0) { editionRepository.updateEditedCondition(any()) }
    }

    @Test
    fun `update changes only selected value`() {
        condition = textCondition(listOf("Marlon", "Ana"))

        viewModel.updateTextToDetect(1, "Júlia")

        verify { editionRepository.updateEditedCondition(condition.copy(texts = listOf("Marlon", "Júlia"))) }
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
}
