package com.buzbuz.smartautoclicker.core.common.overlays.menu

import android.content.Context
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuResizeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayRowSizeTest {
    @Test fun shrinkingCenteredControlsIgnoresTheirPreviousLayoutOffsets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val background = FrameLayout(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val status = View(context)
        content.addView(status, LinearLayout.LayoutParams(248, 48))
        val controls = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            repeat(4) { addView(View(context), LinearLayout.LayoutParams(48, 48)) }
        }
        content.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        background.addView(content)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        background.measure(unspecified, unspecified)
        background.layout(0, 0, background.measuredWidth, background.measuredHeight)
        status.layoutParams = status.layoutParams.apply { width = 136 }
        controls.getChildAt(2).visibility = View.GONE
        controls.getChildAt(3).visibility = View.GONE
        // Android lays out the narrower row inside the old window before the next window resize.
        background.measure(View.MeasureSpec.makeMeasureSpec(248, View.MeasureSpec.EXACTLY), unspecified)
        background.layout(0, 0, background.measuredWidth, background.measuredHeight)
        val controller = OverlayMenuResizeController(background, controls, Size(500, 500)) {}
        assertEquals(136, controller.measureMenuSize(forceContentMeasure = true).width)
    }

    @Test fun shrinkingAStatusRowRemovesItsOldHeightFromTheWindowMeasurement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val background = FrameLayout(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(context).apply { minLines = 3; maxLines = 3; text = "Etapa\nAguardando\nConfirmação" }
        content.addView(status, LinearLayout.LayoutParams(200, ViewGroup.LayoutParams.WRAP_CONTENT))
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            id = View.generateViewId()
            repeat(3) { addView(View(context), LinearLayout.LayoutParams(40, 40)) }
        }
        content.addView(controls)
        background.addView(content)
        val measure = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        background.measure(measure, measure)
        background.layout(0, 0, background.measuredWidth, background.measuredHeight)
        val controller = OverlayMenuResizeController(background, controls, Size(500, 500)) {}
        val expanded = controller.measureMenuSize()
        status.minLines = 1
        status.maxLines = 1
        status.text = "Executando"
        background.measure(View.MeasureSpec.makeMeasureSpec(expanded.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(expanded.height, View.MeasureSpec.AT_MOST))
        background.layout(0, 0, background.measuredWidth, background.measuredHeight)
        assertTrue(controller.measureMenuSize(forceContentMeasure = true).height < expanded.height)
    }

    @Test fun horizontalControlsDoNotAddAnInvisibleWindowBelowTheCard() {
        assertEquals(Size(120, 68), measuredSize(LinearLayout.HORIZONTAL))
    }

    @Test fun verticalControlsPreserveTheirCombinedHeight() {
        assertEquals(Size(100, 148), measuredSize(LinearLayout.VERTICAL))
    }

    private fun measuredSize(orientation: Int): Size {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val background = FrameLayout(context)
        val content = LinearLayout(context).apply { this.orientation = LinearLayout.VERTICAL }
        content.addView(View(context), LinearLayout.LayoutParams(100, 20))
        val controls = LinearLayout(context).apply {
            id = View.generateViewId()
            this.orientation = orientation
            setPadding(0, 4, 0, 4)
            repeat(3) { addView(View(context), LinearLayout.LayoutParams(40, 40)) }
        }
        content.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        background.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        background.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        background.layout(0, 0, background.measuredWidth, background.measuredHeight)
        return OverlayMenuResizeController(background, controls, Size(300, 300)) {}.measureMenuSize()
    }
}
