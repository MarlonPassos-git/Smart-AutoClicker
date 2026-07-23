/*
 * Copyright (C) 2026 Kevin Buzeau
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

import android.graphics.Point
import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.domain.model.IN_AREA
import com.buzbuz.smartautoclicker.core.domain.model.condition.IMAGE_REFERENCES_LIMIT
import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import kotlin.math.max

internal object ImageReferenceEditor {

    fun add(
        condition: ScreenCondition.Image,
        reference: ImageReference,
        screenSize: Point,
    ): ScreenCondition.Image {
        if (condition.references.size >= IMAGE_REFERENCES_LIMIT) return condition

        return condition.withReferences(condition.references + reference, screenSize)
    }

    fun replace(
        condition: ScreenCondition.Image,
        index: Int,
        reference: ImageReference,
        screenSize: Point,
    ): ScreenCondition.Image {
        if (index !in condition.references.indices) return condition
        val references = condition.references.toMutableList().apply { set(index, reference) }

        return condition.withReferences(references, screenSize)
    }

    fun remove(condition: ScreenCondition.Image, index: Int): ScreenCondition.Image {
        if (condition.references.size == 1 || index !in condition.references.indices) return condition
        val references = condition.references.toMutableList().apply { removeAt(index) }

        return condition.copy(references = references)
    }

    fun move(condition: ScreenCondition.Image, from: Int, to: Int): ScreenCondition.Image {
        if (from !in condition.references.indices || to !in condition.references.indices) return condition
        if (from == to) return condition
        val references = condition.references.toMutableList()

        references.add(to, references.removeAt(from))
        return condition.copy(references = references)
    }

    fun reorder(
        condition: ScreenCondition.Image,
        references: List<ImageReference>,
    ): ScreenCondition.Image {
        if (references.size != condition.references.size) return condition

        return condition.copy(references = references)
    }

    fun expandDetectionArea(condition: ScreenCondition.Image, screenSize: Point): ScreenCondition.Image {
        if (condition.detectionType != IN_AREA) return condition
        val currentArea = condition.detectionArea ?: condition.area

        return condition.copy(detectionArea = currentArea.expandFor(condition.references, screenSize))
    }

    fun centerArea(width: Int, height: Int, screenSize: Point): Rect {
        val boundedWidth = width.coerceIn(1, screenSize.x)
        val boundedHeight = height.coerceIn(1, screenSize.y)
        val left = (screenSize.x - boundedWidth) / 2
        val top = (screenSize.y - boundedHeight) / 2

        return Rect(left, top, left + boundedWidth, top + boundedHeight)
    }

    fun positionWithFixedSize(position: Rect, size: Rect, screenSize: Point): Rect {
        val width = size.width().coerceIn(1, screenSize.x)
        val height = size.height().coerceIn(1, screenSize.y)
        val left = (position.centerX() - width / 2).coerceIn(0, screenSize.x - width)
        val top = (position.centerY() - height / 2).coerceIn(0, screenSize.y - height)

        return Rect(left, top, left + width, top + height)
    }

    private fun ScreenCondition.Image.withReferences(
        references: List<ImageReference>,
        screenSize: Point,
    ): ScreenCondition.Image = copy(references = references).let { updated ->
        if (updated.detectionType == IN_AREA) expandDetectionArea(updated, screenSize) else updated
    }

    private fun Rect.expandFor(references: List<ImageReference>, screenSize: Point): Rect {
        val requiredWidth = references.maxOfOrNull { it.area.width() } ?: width()
        val requiredHeight = references.maxOfOrNull { it.area.height() } ?: height()
        val expandedWidth = max(width(), requiredWidth).coerceAtMost(screenSize.x)
        val expandedHeight = max(height(), requiredHeight).coerceAtMost(screenSize.y)

        return centeredBoundedArea(expandedWidth, expandedHeight, screenSize)
    }

    private fun Rect.centeredBoundedArea(width: Int, height: Int, screenSize: Point): Rect {
        val left = (centerX() - width / 2).coerceIn(0, screenSize.x - width)
        val top = (centerY() - height / 2).coerceIn(0, screenSize.y - height)

        return Rect(left, top, left + width, top + height)
    }
}
