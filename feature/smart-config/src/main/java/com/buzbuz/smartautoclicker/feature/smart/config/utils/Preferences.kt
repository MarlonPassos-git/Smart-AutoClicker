/*
 * Copyright (C) 2023 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.feature.smart.config.utils

import android.content.Context
import android.content.SharedPreferences

import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClick
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution


/** @return the shared preferences for the default configuration. */
fun Context.getEventConfigPreferences(): SharedPreferences =
    getSharedPreferences(
        EVENT_CONFIG_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

/** @return the default duration for a click press. */
fun SharedPreferences.getClickPressDurationConfig(context: Context) : Long = getLong(
    PREF_LAST_CLICK_PRESS_DURATION,
    context.resources.getInteger(R.integer.default_click_press_duration).toLong()
)

/** Save a new default duration for the click press. */
fun SharedPreferences.Editor.putClickPressDurationConfig(durationMs: Long) : SharedPreferences.Editor =
    putLong(PREF_LAST_CLICK_PRESS_DURATION, durationMs)

fun SharedPreferences.getAreaClickCountConfig(): Int =
    getInt(PREF_LAST_AREA_CLICK_COUNT, AreaClick.DEFAULT_CLICK_COUNT)

fun SharedPreferences.getAreaClickDistributionConfig(): AreaClickDistribution =
    runCatching { AreaClickDistribution.valueOf(getString(PREF_LAST_AREA_CLICK_DISTRIBUTION, null) ?: "") }
        .getOrDefault(AreaClickDistribution.RANDOM)

fun SharedPreferences.getAreaClickIntervalConfig(): Long =
    getLong(PREF_LAST_AREA_CLICK_INTERVAL, AreaClick.DEFAULT_INTERVAL_MS)

fun SharedPreferences.getAreaClickDurationConfig(): Long =
    getLong(PREF_LAST_AREA_CLICK_DURATION, AreaClick.DEFAULT_PRESS_DURATION_MS)

fun SharedPreferences.Editor.putAreaClickConfig(areaClick: AreaClick): SharedPreferences.Editor =
    putInt(PREF_LAST_AREA_CLICK_COUNT, areaClick.clickCount)
        .putString(PREF_LAST_AREA_CLICK_DISTRIBUTION, areaClick.distribution.name)
        .putLong(PREF_LAST_AREA_CLICK_INTERVAL, areaClick.intervalMs)
        .putLong(PREF_LAST_AREA_CLICK_DURATION, areaClick.pressDurationMs)

/** @return the default duration for a swipe. */
fun SharedPreferences.getSwipeDurationConfig(context: Context) : Long = getLong(
    PREF_LAST_SWIPE_DURATION,
    context.resources.getInteger(R.integer.default_swipe_duration).toLong()
)

/** Save a new default duration for the swipe. */
fun SharedPreferences.Editor.putSwipeDurationConfig(durationMs: Long) : SharedPreferences.Editor =
    putLong(PREF_LAST_SWIPE_DURATION, durationMs)

fun SharedPreferences.getZoomDurationConfig(context: Context): Long = getLong(
    PREF_LAST_ZOOM_DURATION,
    context.resources.getInteger(R.integer.default_swipe_duration).toLong(),
)

fun SharedPreferences.Editor.putZoomDurationConfig(durationMs: Long): SharedPreferences.Editor =
    putLong(PREF_LAST_ZOOM_DURATION, durationMs)

fun SharedPreferences.getZoomIntensityConfig(): Int = getInt(PREF_LAST_ZOOM_INTENSITY, 150)

fun SharedPreferences.Editor.putZoomIntensityConfig(intensityPx: Int): SharedPreferences.Editor =
    putInt(PREF_LAST_ZOOM_INTENSITY, intensityPx)

/** @return the default duration for a pause. */
fun SharedPreferences.getPauseDurationConfig(context: Context) : Long = getLong(
    PREF_LAST_PAUSE_DURATION,
    context.resources.getInteger(R.integer.default_pause_duration).toLong()
)

/** Save a new default duration for the pause. */
fun SharedPreferences.Editor.putPauseDurationConfig(durationMs: Long) : SharedPreferences.Editor =
    putLong(PREF_LAST_PAUSE_DURATION, durationMs)

/** @return the default isAdvanced for the intents. */
fun SharedPreferences.getIntentIsAdvancedConfig(context: Context) : Boolean = getBoolean(
    PREF_LAST_INTENT_IS_ADVANCED,
    context.resources.getBoolean(R.bool.default_intent_isAdvanced)
)

/** Save a new default isAdvanced for the intents. */
fun SharedPreferences.Editor.putIntentIsAdvancedConfig(isAdvanced: Boolean) : SharedPreferences.Editor =
    putBoolean(PREF_LAST_INTENT_IS_ADVANCED, isAdvanced)



/** Event default configuration SharedPreference name. */
private const val EVENT_CONFIG_PREFERENCES_NAME = "EventConfigPreferences"
/** User last click press duration key in the SharedPreferences. */
private const val PREF_LAST_CLICK_PRESS_DURATION = "Last_Click_Press_Duration"
private const val PREF_LAST_AREA_CLICK_COUNT = "Last_Area_Click_Count"
private const val PREF_LAST_AREA_CLICK_DISTRIBUTION = "Last_Area_Click_Distribution"
private const val PREF_LAST_AREA_CLICK_INTERVAL = "Last_Area_Click_Interval"
private const val PREF_LAST_AREA_CLICK_DURATION = "Last_Area_Click_Duration"
/** User last swipe press duration key in the SharedPreferences. */
private const val PREF_LAST_SWIPE_DURATION = "Last_Swipe_Duration"
private const val PREF_LAST_ZOOM_DURATION = "Last_Zoom_Duration"
private const val PREF_LAST_ZOOM_INTENSITY = "Last_Zoom_Intensity"
/** User last pause press duration key in the SharedPreferences. */
private const val PREF_LAST_PAUSE_DURATION = "Last_Pause_Duration"
/** User last pause press duration key in the SharedPreferences. */
private const val PREF_LAST_INTENT_IS_ADVANCED = "Last_Intent_IsAdvanced"
