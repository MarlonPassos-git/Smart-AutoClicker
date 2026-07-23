/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.ui.views.areaselector.AreaSelectorView
import com.buzbuz.smartautoclicker.feature.smart.config.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ReferencePositionSelectorIntegrationSpec {

    @Test
    fun `locked selector moves without changing reference size`() {
        val selector = AreaSelectorView(themedContext(), displayConfigManager())
        val initialArea = Rect(100, 100, 180, 160)
        selector.layout(0, 0, 300, 500)
        selector.setSelectionSizeLocked(true)
        selector.setSelection(initialArea, initialArea)

        drag(selector, fromX = 101f, toX = 61f, y = 130f)

        assertEquals(initialArea.width(), selector.getSelection().width())
        assertEquals(initialArea.height(), selector.getSelection().height())
        assertEquals(60, selector.getSelection().left)
    }

    private fun drag(selector: AreaSelectorView, fromX: Float, toX: Float, y: Float) {
        selector.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, fromX, y, 0))
        selector.onTouchEvent(MotionEvent.obtain(0L, 20L, MotionEvent.ACTION_MOVE, toX, y, 0))
        selector.onTouchEvent(MotionEvent.obtain(0L, 40L, MotionEvent.ACTION_UP, toX, y, 0))
    }

    private fun themedContext(): ContextWrapper = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
        R.style.ScenarioConfigTheme,
    )

    private fun displayConfigManager(): DisplayConfigManager = mock(DisplayConfigManager::class.java).also { manager ->
        `when`(manager.displayConfig).thenReturn(
            DisplayConfig(
                sizePx = Point(300, 500),
                orientation = Configuration.ORIENTATION_PORTRAIT,
                safeInsetTopPx = 0,
                roundedCorners = emptyMap(),
            ),
        )
    }
}
