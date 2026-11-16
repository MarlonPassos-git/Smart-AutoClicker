package com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common

import android.animation.LayoutTransition
import android.content.Context
import android.util.Size
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OverlayResizeTest {
    @Test fun unchangedMenuVisibilityDoesNotBlockSubsequentControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val menu = LinearLayout(context).apply {
            layoutTransition = LayoutTransition()
            addView(View(context), LinearLayout.LayoutParams(60, 60))
            measure(View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
            layout(0, 0, 60, 60)
        }
        val resized = mutableListOf<Size>()
        val controller = OverlayMenuResizeController(menu, menu, Size(200, 200), resized::add)
        try {
            // Running and paused menus have identical visible controls. No transition will end.
            controller.animateLayoutChanges { menu.getChildAt(0).visibility = View.VISIBLE }
            assertFalse("Retomar and Parar must remain clickable", controller.isAnimating)
            assertEquals(Size(60, 60), resized.last())

            var nextControlHandled = false
            controller.animateLayoutChanges { nextControlHandled = true }
            assertEquals(true, nextControlHandled)
            assertFalse(controller.isAnimating)
        } finally { controller.release() }
    }
}
