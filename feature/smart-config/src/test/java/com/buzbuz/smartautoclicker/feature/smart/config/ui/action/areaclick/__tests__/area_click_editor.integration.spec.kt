package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick.__tests__

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick.AreaClickEditorMenu
import com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick.AreaClickEditorView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class AreaClickEditorIntegrationSpec {

    private lateinit var editor: AreaClickEditorView

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        editor = AreaClickEditorView(context)
        editor.measure(exactly(1_000), exactly(600))
        editor.layout(0, 0, 1_000, 600)
    }

    @Test
    fun `empty area creates centered default rectangle`() {
        val density = editor.resources.displayMetrics.density
        val vertices = editor.getVertices()

        assertEquals(4, vertices.size)
        assertEquals((230 * density).toInt(), vertices[1].x - vertices[0].x)
        assertEquals((130 * density).toInt(), vertices[3].y - vertices[0].y)
        assertTrue(editor.isValid)
    }

    @Test
    fun `area editing uses transparent screen overlay instead of opaque dialog`() {
        assertTrue(OverlayMenu::class.java.isAssignableFrom(AreaClickEditorMenu::class.java))
        assertFalse(OverlayDialog::class.java.isAssignableFrom(AreaClickEditorMenu::class.java))
    }

    @Test
    fun `configured area survives editor initialization before layout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val restoredEditor = AreaClickEditorView(context)

        restoredEditor.setVertices(rectangle())
        restoredEditor.measure(exactly(1_000), exactly(600))
        restoredEditor.layout(0, 0, 1_000, 600)

        assertEquals(rectangle(), restoredEditor.getVertices())
    }

    @Test
    fun `add and remove respect vertex limits`() {
        repeat(10) { editor.addVertex() }
        assertEquals(8, editor.getVertices().size)

        repeat(10) { editor.removeVertex() }
        assertEquals(3, editor.getVertices().size)
    }

    @Test
    fun `crossing edges disable area confirmation`() {
        editor.setVertices(listOf(Point(100, 100), Point(300, 300), Point(100, 300), Point(300, 100)))

        assertFalse(editor.isValid)
    }

    @Test
    fun `dragging vertex moves only selected corner`() {
        editor.setVertices(rectangle())
        drag(100, 100, 125, 135)

        assertEquals(listOf(Point(125, 135), Point(300, 100), Point(300, 300), Point(100, 300)), editor.getVertices())
    }

    @Test
    fun `dragging edge translates both endpoints`() {
        editor.setVertices(rectangle())
        drag(200, 100, 215, 125)

        assertEquals(listOf(Point(115, 125), Point(315, 125), Point(300, 300), Point(100, 300)), editor.getVertices())
    }

    @Test
    fun `dragging interior translates complete polygon`() {
        editor.setVertices(rectangle())
        drag(200, 200, 240, 230)

        assertEquals(listOf(Point(140, 130), Point(340, 130), Point(340, 330), Point(140, 330)), editor.getVertices())
    }

    private fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        editor.onTouchEvent(event(MotionEvent.ACTION_DOWN, fromX, fromY))
        editor.onTouchEvent(event(MotionEvent.ACTION_MOVE, toX, toY))
        editor.onTouchEvent(event(MotionEvent.ACTION_UP, toX, toY))
    }

    private fun event(action: Int, x: Int, y: Int): MotionEvent =
        MotionEvent.obtain(0, 0, action, x.toFloat(), y.toFloat(), 0)

    private fun rectangle(): List<Point> =
        listOf(Point(100, 100), Point(300, 100), Point(300, 300), Point(100, 300))

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
