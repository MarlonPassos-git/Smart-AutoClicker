package com.buzbuz.smartautoclicker.core.domain.model.action.mapper

import android.graphics.Point

internal object AreaClickVerticesCodec {
    private const val PREFIX = "v1:"
    private const val EXPECTED_FORMAT = "v1:x,y;x,y;... with 3..8 integer vertices"

    fun encode(vertices: List<Point>): String =
        PREFIX + vertices.joinToString(";") { point -> "${point.x},${point.y}" }

    fun decode(encoded: String): List<Point> {
        val points = runCatching { parsePoints(encoded) }.getOrNull()
        if (points == null || points.size !in 3..8) malformed(encoded)
        return points
    }

    private fun parsePoints(encoded: String): List<Point> {
        if (!encoded.startsWith(PREFIX)) malformed(encoded)
        return encoded.removePrefix(PREFIX).split(';').map { encodedPoint ->
            val coordinates = encodedPoint.split(',')
            if (coordinates.size != 2) malformed(encoded)
            Point(coordinates[0].toInt(), coordinates[1].toInt())
        }
    }

    private fun malformed(encoded: String): Nothing =
        throw IllegalArgumentException("Malformed area-click vertices '$encoded'; expected $EXPECTED_FORMAT")
}
