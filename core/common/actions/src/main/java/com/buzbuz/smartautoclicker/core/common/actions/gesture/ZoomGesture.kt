/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.common.actions.gesture

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.base.extensions.nextLongInOffset
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.common.actions.utils.MAXIMUM_STROKE_DURATION_MS
import com.buzbuz.smartautoclicker.core.common.actions.utils.MINIMUM_STROKE_DURATION_MS
import com.buzbuz.smartautoclicker.core.common.actions.utils.RANDOMIZATION_DURATION_MAX_OFFSET_MS
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

data class ZoomStrokeEndpoints(val from: Point, val to: Point)

fun calculateZoomStrokeEndpoints(
    center: Point,
    intensityPx: Int,
    direction: ZoomDirection,
    innerRadiusPx: Int,
    screenWidthPx: Int,
    screenHeightPx: Int,
): Pair<ZoomStrokeEndpoints, ZoomStrokeEndpoints> {
    val maximumRadiusPx = calculateMaximumDiagonalRadius(center, screenWidthPx, screenHeightPx)
    val actualInnerRadiusPx = min(innerRadiusPx, maximumRadiusPx)
    val requestedOuterRadiusPx = actualInnerRadiusPx + intensityPx.coerceAtLeast(0)
    val outerRadiusPx = min(requestedOuterRadiusPx, maximumRadiusPx)
    val innerPoints = symmetricDiagonalPoints(center, actualInnerRadiusPx)
    val outerPoints = symmetricDiagonalPoints(center, outerRadiusPx)

    return if (direction == ZoomDirection.IN) {
        ZoomStrokeEndpoints(innerPoints.first, outerPoints.first) to
            ZoomStrokeEndpoints(innerPoints.second, outerPoints.second)
    } else {
        ZoomStrokeEndpoints(outerPoints.first, innerPoints.first) to
            ZoomStrokeEndpoints(outerPoints.second, innerPoints.second)
    }
}

fun GestureDescription.Builder.buildZoomGesture(
    strokes: Pair<ZoomStrokeEndpoints, ZoomStrokeEndpoints>,
    durationMs: Long,
    random: Random?,
): GestureDescription {
    val actualDurationMs = durationMs.toNormalizedZoomDuration(random)
    addZoomStroke(strokes.first, actualDurationMs)
    addZoomStroke(strokes.second, actualDurationMs)
    return build()
}

private fun GestureDescription.Builder.addZoomStroke(
    endpoints: ZoomStrokeEndpoints,
    durationMs: Long,
) {
    val path = Path().apply { line(endpoints.from, endpoints.to, null) }
    addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
}

private fun Long.toNormalizedZoomDuration(random: Random?): Long {
    val randomizedDuration = random?.nextLongInOffset(this, RANDOMIZATION_DURATION_MAX_OFFSET_MS) ?: this
    return randomizedDuration.coerceIn(MINIMUM_STROKE_DURATION_MS, MAXIMUM_STROKE_DURATION_MS)
}

private fun calculateMaximumDiagonalRadius(center: Point, width: Int, height: Int): Int {
    val horizontalRadius = min(center.x, width - center.x).coerceAtLeast(0)
    val verticalRadius = min(center.y, height - center.y).coerceAtLeast(0)
    return floor(min(horizontalRadius, verticalRadius) * sqrt(2.0)).toInt()
}

private fun symmetricDiagonalPoints(center: Point, radiusPx: Int): Pair<Point, Point> {
    val componentPx = floor(radiusPx / sqrt(2.0)).toInt()
    return Point(center.x - componentPx, center.y - componentPx) to
        Point(center.x + componentPx, center.y + componentPx)
}
