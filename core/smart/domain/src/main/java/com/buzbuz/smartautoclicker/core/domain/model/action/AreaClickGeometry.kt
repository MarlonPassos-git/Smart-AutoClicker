package com.buzbuz.smartautoclicker.core.domain.model.action

import android.graphics.Point
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** Shared polygon validation and click sampling for area-click configuration and execution. */
object AreaClickGeometry {

    /** Returns whether vertices form a simple positive-area polygon with 3 to 8 vertices. */
    fun isValidPolygon(vertices: List<Point>): Boolean =
        vertices.size in 3..8 && abs(signedDoubleArea(vertices)) > 0L && !hasCrossingEdges(vertices)

    /** Returns true only for points strictly inside the polygon, excluding its contour. */
    fun containsStrictly(vertices: List<Point>, point: Point): Boolean {
        if (isOnContour(vertices, point)) return false
        return rayCrossingContains(vertices, point)
    }

    /** Generates exactly [count] points for a valid polygon using the requested distribution. */
    fun sample(
        vertices: List<Point>,
        count: Int,
        distribution: AreaClickDistribution,
        random: Random = Random.Default,
    ): List<Point> {
        require(isValidPolygon(vertices)) { "Invalid polygon $vertices; expected 3..8 ordered vertices forming a simple positive-area polygon" }
        require(count in AreaClick.CLICK_COUNT_RANGE) { "Invalid click count $count; expected 1..50" }
        val triangles = triangulate(vertices)
        return when (distribution) {
            AreaClickDistribution.RANDOM -> List(count) { sampleInside(vertices, triangles, random) }
            AreaClickDistribution.DISTRIBUTED -> sampleDistributed(vertices, triangles, count, random).shuffled(random)
        }
    }

    private fun sampleDistributed(
        polygon: List<Point>, triangles: List<Triangle>, count: Int, random: Random,
    ): List<Point> {
        val selected = mutableListOf(sampleInside(polygon, triangles, random))
        repeat(count - 1) {
            val candidates = List(DISTRIBUTED_CANDIDATES) { sampleInside(polygon, triangles, random) }
            selected += candidates.maxBy { candidate -> minimumDistanceSquared(candidate, selected) }
        }
        return selected
    }

    private fun minimumDistanceSquared(candidate: Point, selected: List<Point>): Long =
        selected.minOf { point ->
            val dx = candidate.x.toLong() - point.x
            val dy = candidate.y.toLong() - point.y
            dx * dx + dy * dy
        }

    private fun sampleInside(polygon: List<Point>, triangles: List<Triangle>, random: Random): Point {
        repeat(SAMPLE_ATTEMPTS) {
            val sampled = sampleTriangle(selectWeightedTriangle(triangles, random), random)
            if (containsStrictly(polygon, sampled)) return sampled
        }
        return findInteriorInteger(polygon)
    }

    private fun selectWeightedTriangle(triangles: List<Triangle>, random: Random): Triangle {
        val totalArea = triangles.sumOf { it.area }
        var target = random.nextDouble() * totalArea
        for (triangle in triangles) {
            target -= triangle.area
            if (target < 0.0) return triangle
        }
        return triangles.last()
    }

    private fun sampleTriangle(triangle: Triangle, random: Random): Point {
        val firstWeight = sqrt(interiorRandom(random))
        val secondWeight = interiorRandom(random)
        val a = 1.0 - firstWeight
        val b = firstWeight * (1.0 - secondWeight)
        val c = firstWeight * secondWeight
        return Point(
            (a * triangle.a.x + b * triangle.b.x + c * triangle.c.x).toInt(),
            (a * triangle.a.y + b * triangle.b.y + c * triangle.c.y).toInt(),
        )
    }

    private fun interiorRandom(random: Random): Double =
        RANDOM_EPSILON + random.nextDouble() * (1.0 - 2.0 * RANDOM_EPSILON)

    private fun findInteriorInteger(polygon: List<Point>): Point {
        val minX = polygon.minOf(Point::x)
        val maxX = polygon.maxOf(Point::x)
        val minY = polygon.minOf(Point::y)
        val maxY = polygon.maxOf(Point::y)
        for (y in minY..maxY) for (x in minX..maxX) {
            val candidate = Point(x, y)
            if (containsStrictly(polygon, candidate)) return candidate
        }
        throw IllegalArgumentException("Polygon $polygon has no integer interior point; expected a clickable screen area")
    }

    private fun triangulate(vertices: List<Point>): List<Triangle> {
        val remaining = vertices.indices.toMutableList()
        val triangles = mutableListOf<Triangle>()
        val orientation = signedDoubleArea(vertices).sign()
        while (remaining.size > 3) clipOneEar(vertices, remaining, triangles, orientation)
        triangles += Triangle(vertices[remaining[0]], vertices[remaining[1]], vertices[remaining[2]])
        return triangles
    }

    private fun clipOneEar(
        vertices: List<Point>, remaining: MutableList<Int>, triangles: MutableList<Triangle>, orientation: Int,
    ) {
        val earPosition = remaining.indices.firstOrNull { position ->
            isEar(vertices, remaining, position, orientation)
        } ?: throw IllegalArgumentException("Invalid polygon $vertices; expected a simple polygon suitable for triangulation")
        val previous = remaining[(earPosition - 1 + remaining.size) % remaining.size]
        val current = remaining[earPosition]
        val next = remaining[(earPosition + 1) % remaining.size]
        triangles += Triangle(vertices[previous], vertices[current], vertices[next])
        remaining.removeAt(earPosition)
    }

    private fun isEar(vertices: List<Point>, indices: List<Int>, position: Int, orientation: Int): Boolean {
        val previous = vertices[indices[(position - 1 + indices.size) % indices.size]]
        val current = vertices[indices[position]]
        val next = vertices[indices[(position + 1) % indices.size]]
        if (cross(previous, current, next).sign() != orientation) return false
        return indices.none { index ->
            val point = vertices[index]
            point != previous && point != current && point != next && pointInTriangle(point, previous, current, next)
        }
    }

    private fun pointInTriangle(point: Point, a: Point, b: Point, c: Point): Boolean {
        val first = cross(a, b, point)
        val second = cross(b, c, point)
        val third = cross(c, a, point)
        return (first >= 0 && second >= 0 && third >= 0) || (first <= 0 && second <= 0 && third <= 0)
    }

    private fun hasCrossingEdges(vertices: List<Point>): Boolean = vertices.indices.any { first ->
        vertices.indices.any { second ->
            !edgesAreAdjacent(first, second, vertices.size) && segmentsIntersect(
                vertices[first], vertices[(first + 1) % vertices.size],
                vertices[second], vertices[(second + 1) % vertices.size],
            )
        }
    }

    private fun edgesAreAdjacent(first: Int, second: Int, size: Int): Boolean =
        first == second || (first + 1) % size == second || (second + 1) % size == first

    private fun segmentsIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
        val abc = cross(a, b, c)
        val abd = cross(a, b, d)
        val cda = cross(c, d, a)
        val cdb = cross(c, d, b)
        if (abc == 0L && onSegment(a, b, c) || abd == 0L && onSegment(a, b, d)) return true
        if (cda == 0L && onSegment(c, d, a) || cdb == 0L && onSegment(c, d, b)) return true
        return abc.sign() != abd.sign() && cda.sign() != cdb.sign()
    }

    private fun isOnContour(vertices: List<Point>, point: Point): Boolean = vertices.indices.any { index ->
        val next = (index + 1) % vertices.size
        cross(vertices[index], vertices[next], point) == 0L && onSegment(vertices[index], vertices[next], point)
    }

    private fun rayCrossingContains(vertices: List<Point>, point: Point): Boolean {
        var inside = false
        var previous = vertices.last()
        for (current in vertices) {
            val crosses = (current.y > point.y) != (previous.y > point.y)
            val intersectionX = (previous.x - current.x).toDouble() * (point.y - current.y) /
                    (previous.y - current.y).toDouble() + current.x
            if (crosses && point.x < intersectionX) inside = !inside
            previous = current
        }
        return inside
    }

    private fun onSegment(a: Point, b: Point, point: Point): Boolean =
        point.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && point.y in minOf(a.y, b.y)..maxOf(a.y, b.y)

    private fun cross(a: Point, b: Point, c: Point): Long =
        (b.x - a.x).toLong() * (c.y - a.y) - (b.y - a.y).toLong() * (c.x - a.x)

    private fun signedDoubleArea(vertices: List<Point>): Long = vertices.indices.sumOf { index ->
        val next = vertices[(index + 1) % vertices.size]
        vertices[index].x.toLong() * next.y - next.x.toLong() * vertices[index].y
    }

    private fun Long.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }

    private data class Triangle(val a: Point, val b: Point, val c: Point) {
        val area: Double = abs(crossValue(a, b, c)) / 2.0

        companion object {
            private fun crossValue(a: Point, b: Point, c: Point): Long =
                (b.x - a.x).toLong() * (c.y - a.y) - (b.y - a.y).toLong() * (c.x - a.x)
        }
    }

    private const val DISTRIBUTED_CANDIDATES = 64
    private const val SAMPLE_ATTEMPTS = 128
    private const val RANDOM_EPSILON = 0.000001
}
