/* Copyright (C) 2026 Kevin Buzeau */
package com.buzbuz.smartautoclicker.core.common.actions.gesture.__tests__

import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.common.actions.gesture.ZoomStrokeEndpoints
import com.buzbuz.smartautoclicker.core.common.actions.gesture.calculateZoomStrokeEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ZoomGestureTest {

    @Test
    fun zoomIn_movesBothFingersAwayFromCenter() {
        val strokes = calculateZoomStrokeEndpoints(
            center = Point(500, 500),
            intensityPx = 100,
            direction = ZoomDirection.IN,
            innerRadiusPx = 20,
            screenWidthPx = 1_000,
            screenHeightPx = 1_000,
        )

        assertEquals(ZoomStrokeEndpoints(Point(486, 486), Point(416, 416)), strokes.first)
        assertEquals(ZoomStrokeEndpoints(Point(514, 514), Point(584, 584)), strokes.second)
    }

    @Test
    fun zoomOut_reversesZoomInPaths() {
        val strokes = calculateZoomStrokeEndpoints(
            center = Point(500, 500),
            intensityPx = 100,
            direction = ZoomDirection.OUT,
            innerRadiusPx = 20,
            screenWidthPx = 1_000,
            screenHeightPx = 1_000,
        )

        assertEquals(ZoomStrokeEndpoints(Point(416, 416), Point(486, 486)), strokes.first)
        assertEquals(ZoomStrokeEndpoints(Point(584, 584), Point(514, 514)), strokes.second)
    }

    @Test
    fun centerNearEdge_keepsEveryEndpointInsideScreen() {
        val strokes = calculateZoomStrokeEndpoints(
            center = Point(10, 10),
            intensityPx = 500,
            direction = ZoomDirection.IN,
            innerRadiusPx = 24,
            screenWidthPx = 200,
            screenHeightPx = 200,
        )

        listOf(strokes.first.from, strokes.first.to, strokes.second.from, strokes.second.to).forEach { point ->
            assertTrue(point.x in 0..200)
            assertTrue(point.y in 0..200)
        }
    }
}
