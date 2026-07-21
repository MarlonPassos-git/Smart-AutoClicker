/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.domain.model.action

import android.graphics.Point
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier

/**
 * Changes visible content scale with a symmetric two-finger gesture.
 * Example: `Zoom(id, eventId, "Map zoom", 0, ZoomDirection.IN, 300, Point(500, 500), 250)`.
 */
data class Zoom(
    override val id: Identifier,
    override val eventId: Identifier,
    override val name: String? = null,
    override var priority: Int,
    val direction: ZoomDirection = ZoomDirection.IN,
    val intensityPx: Int? = null,
    val center: Point? = null,
    val zoomDurationMs: Long? = null,
) : Action() {

    override fun isComplete(): Boolean =
        super.isComplete() && intensityPx != null && intensityPx > 0 && center != null && zoomDurationMs != null

    override fun hashCodeNoIds(): Int =
        name.hashCode() + direction.hashCode() + intensityPx.hashCode() + center.hashCode() + zoomDurationMs.hashCode()

    override fun deepCopy(): Zoom = copy(name = "" + name, center = center?.let { Point(it) })
}
