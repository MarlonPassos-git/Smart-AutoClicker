/*
 * Copyright (C) 2024 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.feature.smart.config.domain

import android.content.Context

import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.ConditionOperator
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.action.Click
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.core.domain.model.action.ToggleEvent
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getClickPressDurationConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getAreaClickCountConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getAreaClickDistributionConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getAreaClickDurationConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getAreaClickIntervalConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getEventConfigPreferences
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getIntentIsAdvancedConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getPauseDurationConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getSwipeDurationConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getZoomDurationConfig
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getZoomIntensityConfig

internal class EditionDefaultValues {

    fun eventName(context: Context): String =
        context.getString(R.string.default_event_name)
    @ConditionOperator fun eventConditionOperator(): Int =
        AND

    fun conditionName(context: Context): String =
        context.getString(R.string.default_condition_name)
    fun conditionThreshold(context: Context): Int =
        context.resources.getInteger(R.integer.default_condition_threshold)
    fun conditionDetectionType(): Int =
        EXACT
    fun conditionShouldBeDetected(): Boolean =
        true

    fun clickName(context: Context): String =
        context.getString(R.string.default_click_name)
    fun clickPressDuration(context: Context): Long =
        context.getEventConfigPreferences().getClickPressDurationConfig(context)
    fun clickPositionType(): Click.PositionType =
        Click.PositionType.USER_SELECTED

    fun areaClickName(context: Context): String = context.getString(R.string.default_area_click_name)
    fun areaClickCount(context: Context): Int = context.getEventConfigPreferences().getAreaClickCountConfig()
    fun areaClickDistribution(context: Context): AreaClickDistribution =
        context.getEventConfigPreferences().getAreaClickDistributionConfig()
    fun areaClickDuration(context: Context): Long = context.getEventConfigPreferences().getAreaClickDurationConfig()
    fun areaClickInterval(context: Context): Long = context.getEventConfigPreferences().getAreaClickIntervalConfig()

    fun swipeName(context: Context): String =
        context.getString(R.string.default_swipe_name)
    fun swipeDuration(context: Context): Long =
        context.getEventConfigPreferences().getSwipeDurationConfig(context)

    fun zoomName(context: Context): String = context.getString(R.string.zoom_default_name)
    fun zoomDuration(context: Context): Long = context.getEventConfigPreferences().getZoomDurationConfig(context)
    fun zoomIntensity(context: Context): Int = context.getEventConfigPreferences().getZoomIntensityConfig()

    fun pauseName(context: Context): String =
        context.getString(R.string.default_pause_name)
    fun pauseDuration(context: Context): Long =
        context.getEventConfigPreferences().getPauseDurationConfig(context)

    fun intentName(context: Context): String =
        context.getString(R.string.default_intent_name)
    fun intentIsAdvanced(context: Context): Boolean =
        context.getEventConfigPreferences().getIntentIsAdvancedConfig(context)

    fun toggleEventName(context: Context): String =
        context.getString(R.string.default_toggle_event_name)
    fun eventToggleType(): ToggleEvent.ToggleType =
        ToggleEvent.ToggleType.ENABLE

    fun changeCounterName(context: Context): String =
        context.getString(R.string.default_change_counter_name)

    fun notificationName(context: Context): String =
        context.getString(R.string.default_notification_name)

    fun systemActionName(context: Context): String =
        context.getString(R.string.default_system_action_name)

    fun setTextName(context: Context): String =
        context.getString(R.string.default_set_text_name)

    fun counterComparisonOperation(): ComparisonOperation =
        ComparisonOperation.EQUALS
}
