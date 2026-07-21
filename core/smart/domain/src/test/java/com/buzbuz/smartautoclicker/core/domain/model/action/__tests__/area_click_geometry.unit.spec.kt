package com.buzbuz.smartautoclicker.core.domain.model.action.__tests__

import android.graphics.Point
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class AreaClickGeometryTest {

    @Test
    fun polygonValidationAcceptsConvexTiltedAndConcaveShapes() {
        assertTrue(AreaClickGeometry.isValidPolygon(triangle))
        assertTrue(AreaClickGeometry.isValidPolygon(tiltedRectangle))
        assertTrue(AreaClickGeometry.isValidPolygon(concavePolygon))
    }

    @Test
    fun polygonValidationRejectsCrossingNullAreaAndVertexLimits() {
        assertFalse(AreaClickGeometry.isValidPolygon(crossingPolygon))
        assertFalse(AreaClickGeometry.isValidPolygon(listOf(Point(0, 0), Point(5, 5), Point(10, 10))))
        assertFalse(AreaClickGeometry.isValidPolygon(triangle.take(2)))
        assertFalse(AreaClickGeometry.isValidPolygon(List(9) { Point(it, it * it) }))
    }

    @Test
    fun deterministicRandomSamplingReturnsExactStrictlyInteriorCount() {
        val sample = AreaClickGeometry.sample(concavePolygon, 50, AreaClickDistribution.RANDOM, Random(7))

        assertEquals(50, sample.size)
        assertTrue(sample.all { AreaClickGeometry.containsStrictly(concavePolygon, it) })
    }

    @Test
    fun distributedSamplingUsesBestEffortSeparation() {
        val randomPoints = AreaClickGeometry.sample(tiltedRectangle, 12, AreaClickDistribution.RANDOM, Random(22))
        val distributed = AreaClickGeometry.sample(tiltedRectangle, 12, AreaClickDistribution.DISTRIBUTED, Random(22))

        assertTrue(minimumPairDistance(distributed) >= minimumPairDistance(randomPoints))
    }

    private fun minimumPairDistance(points: List<Point>): Long = points.indices.minOf { first ->
        points.indices.filter { it != first }.minOf { second ->
            val dx = points[first].x.toLong() - points[second].x
            val dy = points[first].y.toLong() - points[second].y
            dx * dx + dy * dy
        }
    }

    private val triangle = listOf(Point(10, 10), Point(200, 20), Point(80, 180))
    private val tiltedRectangle = listOf(Point(30, 20), Point(240, 70), Point(210, 190), Point(0, 140))
    private val concavePolygon = listOf(Point(0, 0), Point(200, 0), Point(200, 200), Point(100, 90), Point(0, 200))
    private val crossingPolygon = listOf(Point(0, 0), Point(200, 200), Point(0, 200), Point(200, 0))
}
