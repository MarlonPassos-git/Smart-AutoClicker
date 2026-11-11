package com.buzbuz.smartautoclicker.core.common.overlays.menu

import android.graphics.Point
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MenuWindowBoundsTest {
    @Test fun expandingAMenuKeepsItsControlsInsideTheDisplay() {
        val window = WindowManager.LayoutParams().apply { width = 100; height = 100; x = 2240; y = 900 }
        val display = Point(2340, 1080)
        window.fitMenuOnScreen(display)
        window.width = 700
        window.height = 600
        window.fitMenuOnScreen(display)
        assertEquals(1640, window.x)
        assertEquals(480, window.y)
    }

    @Test fun aPositionThatStillFitsIsPreserved() {
        val window = WindowManager.LayoutParams().apply { width = 700; height = 600; x = 200; y = 100 }
        window.fitMenuOnScreen(Point(2340, 1080))
        assertEquals(200, window.x)
        assertEquals(100, window.y)
    }

    @Test fun anOversizedMenuRemainsAnchoredAtTheReachableOrigin() {
        val window = WindowManager.LayoutParams().apply { width = 700; height = 1200; x = -50; y = 900 }
        window.fitMenuOnScreen(Point(600, 1080))
        assertEquals(0, window.x)
        assertEquals(0, window.y)
    }
}
