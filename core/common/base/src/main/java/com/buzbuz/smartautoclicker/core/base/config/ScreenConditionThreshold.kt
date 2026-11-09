/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.base.config

import com.buzbuz.smartautoclicker.core.extensions.BuildConfig

/** Tolerance limits shared by editors, live tests and backup import, in percent. */
object ScreenConditionThreshold {
    const val MIN = BuildConfig.SCREEN_CONDITION_THRESHOLD_MIN
    const val MAX = BuildConfig.SCREEN_CONDITION_THRESHOLD_MAX
}
