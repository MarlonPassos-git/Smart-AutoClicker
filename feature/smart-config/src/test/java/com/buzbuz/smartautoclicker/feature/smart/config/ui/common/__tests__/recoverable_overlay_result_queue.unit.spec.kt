/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.common

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoverableOverlayResultQueueUnitSpec {

    @Test
    fun `result emitted while overlay is hidden reaches later collector`() = runTest {
        val resultQueue = RecoverableOverlayResultQueue<String>()

        resultQueue.enqueue("retry")

        assertEquals("retry", resultQueue.results.first())
    }
}
