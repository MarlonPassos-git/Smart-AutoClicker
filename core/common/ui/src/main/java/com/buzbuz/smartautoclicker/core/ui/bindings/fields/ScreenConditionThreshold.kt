/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.ui.bindings.fields

import com.buzbuz.smartautoclicker.core.base.config.ScreenConditionThreshold
import com.google.android.material.slider.Slider

/**
 * Displays a persisted tolerance safely, including backups outside the current limits.
 * For example: `slider.setScreenConditionThreshold(condition.threshold)`.
 * Only the displayed value is bounded; this does not rewrite the stored condition.
 */
fun Slider.setScreenConditionThreshold(threshold: Int) {
    valueFrom = ScreenConditionThreshold.MIN.toFloat()
    valueTo = ScreenConditionThreshold.MAX.toFloat()
    value = threshold.coerceIn(ScreenConditionThreshold.MIN, ScreenConditionThreshold.MAX).toFloat()
}
