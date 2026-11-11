package com.buzbuz.smartautoclicker.feature.script.config

import android.app.Activity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import org.robolectric.Robolectric
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptControlLayoutTest {
    @Test fun injectedTouchesRequestRecoveryWithoutClickingTheControl() {
        verifyTouch(MotionEvent.TOOL_TYPE_UNKNOWN, expectedClicks = 0, expectedRecoveries = 1)
    }

    @Test fun physicalFingerStillOperatesTheControlWithAVirtualDeviceId() {
        verifyTouch(MotionEvent.TOOL_TYPE_FINGER, expectedClicks = 1, expectedRecoveries = 0)
    }

    @Test fun physicalStylusStillOperatesTheControl() {
        verifyTouch(MotionEvent.TOOL_TYPE_STYLUS, expectedClicks = 1, expectedRecoveries = 0)
    }

    private fun verifyTouch(toolType: Int, expectedClicks: Int, expectedRecoveries: Int) {
        val context = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        var clicks = 0
        var recoveries = 0
        val layout = ScriptControlLayout(context).apply {
            onScriptTouch = { recoveries++ }
            addView(Button(context).apply { isFocusable = false; setOnClickListener { clicks++ } }, FrameLayout.LayoutParams(100, 100))
        }
        context.setContentView(layout)
        val measure = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        layout.measure(measure, measure)
        layout.layout(0, 0, 100, 100)
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to 100L, MotionEvent.ACTION_MOVE to 110L, MotionEvent.ACTION_UP to 120L)) {
            val event = MotionEvent.obtain(100, time, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }),
                arrayOf(MotionEvent.PointerCoords().apply { x = 50f; y = 50f; pressure = 1f; size = 1f }),
                0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertEquals(toolType, event.getToolType(0))
                layout.dispatchTouchEvent(event)
            } finally { event.recycle() }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(expectedClicks, clicks)
        assertEquals(expectedRecoveries, recoveries)
    }
}
