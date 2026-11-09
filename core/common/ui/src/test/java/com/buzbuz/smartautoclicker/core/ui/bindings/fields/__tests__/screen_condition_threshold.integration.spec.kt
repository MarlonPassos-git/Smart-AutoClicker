/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.ui.bindings.fields

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.ui.R
import com.buzbuz.smartautoclicker.core.ui.databinding.IncludeFieldSliderBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ScreenConditionThresholdIntegrationSpec {

    @Test
    fun `editor opens with saved tolerance of 25 percent`() {
        assertSliderRenders(25, 25f)
    }

    @Test
    fun `editor accepts maximum tolerance of 30 percent`() {
        assertSliderRenders(30, 30f)
    }

    @Test
    fun `out of range backup values cannot crash the editor`() {
        assertSliderRenders(99, 30f)
        assertSliderRenders(-1, 0f)
    }

    private fun assertSliderRenders(savedThreshold: Int, expected: Float) {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.AppTheme,
        )
        val binding = IncludeFieldSliderBinding.inflate(LayoutInflater.from(context))
        binding.slider.setScreenConditionThreshold(savedThreshold)
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        // Material validates the range during layout/drawing, where the reported crash occurred.
        binding.root.layout(0, 0, 600, 200)
        binding.root.draw(Canvas(Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)))

        assertEquals(expected, binding.slider.value, 0f)
        assertEquals(0f, binding.slider.valueFrom, 0f)
        assertEquals(30f, binding.slider.valueTo, 0f)
    }
}
