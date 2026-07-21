/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers

import android.graphics.Canvas
import android.graphics.PointF
import android.view.View
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.ItemBriefDescription
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.ItemBriefRenderer
import kotlin.math.sqrt

internal class ZoomBriefRenderer(
    briefView: View,
    viewStyle: SwipeBriefRendererStyle,
) : ItemBriefRenderer<SwipeBriefRendererStyle>(briefView, viewStyle) {

    private var description: ZoomDescription? = null

    override fun onNewDescription(description: ItemBriefDescription, animate: Boolean) {
        this.description = description as? ZoomDescription
    }

    override fun onInvalidate() = Unit

    override fun onDraw(canvas: Canvas) {
        val zoom = description ?: return
        val center = zoom.center ?: return
        val inner = diagonalPointPair(center, ZOOM_PREVIEW_INNER_RADIUS_PX)
        val outer = diagonalPointPair(center, ZOOM_PREVIEW_INNER_RADIUS_PX + zoom.intensityPx)
        val strokes = if (zoom.direction == ZoomDirection.IN) inner zip outer else outer zip inner

        strokes.forEach { (from, to) ->
            canvas.drawLine(from.x, from.y, to.x, to.y, viewStyle.linePaint)
            canvas.drawCircle(from.x, from.y, viewStyle.outerRadiusPx, viewStyle.outerFromPaint)
            canvas.drawCircle(to.x, to.y, viewStyle.outerRadiusPx, viewStyle.outerToPaint)
        }
    }

    override fun onStop() {
        description = null
    }

    private fun diagonalPointPair(center: PointF, radius: Float): List<PointF> {
        val component = radius / sqrt(2f)
        return listOf(
            PointF(center.x - component, center.y - component),
            PointF(center.x + component, center.y + component),
        )
    }
}

data class ZoomDescription(
    val direction: ZoomDirection = ZoomDirection.IN,
    val center: PointF? = null,
    val intensityPx: Int = DEFAULT_ZOOM_INTENSITY_PX,
    val zoomDurationMs: Long = 250L,
) : ItemBriefDescription

const val DEFAULT_ZOOM_INTENSITY_PX = 150
const val ZOOM_PREVIEW_INNER_RADIUS_PX = 24f
