package com.buzbuz.smartautoclicker.core.script.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LatencyTargetIntegrationTest {
    @Test
    fun optionalRecoveryDialogCanBeDismissedWithoutChangingTheTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context, LatencyTargetActivity::class.java)
            .putExtra("recoveryDialog", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LatencyTargetActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = checkNotNull(activity.recoveryDialog)
                assertTrue(dialog.isShowing)
                assertEquals(0, activity.target.generation)
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(checkNotNull(activity.recoveryDialog).isShowing)
                assertEquals(0, activity.target.generation)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun targetChangesItsRenderedColorOnlyAfterACompletedTouchInItsRectangle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context, LatencyTargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LatencyTargetActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val target = activity.target
                val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    fun color(): Int {
                        target.draw(canvas)
                        return bitmap.getPixel(target.width / 2, (target.height * .8).toInt())
                    }
                    fun touch(action: Int, x: Float, y: Float) {
                        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
                        try { target.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    val x = target.width / 2f
                    val y = target.height * .8f
                    assertEquals(Color.GREEN, color())
                    touch(MotionEvent.ACTION_DOWN, x, y)
                    assertEquals(Color.GREEN, color())
                    touch(MotionEvent.ACTION_CANCEL, x, y)
                    touch(MotionEvent.ACTION_UP, x, y)
                    assertEquals(0, target.generation)
                    touch(MotionEvent.ACTION_DOWN, 0f, 0f)
                    touch(MotionEvent.ACTION_UP, 0f, 0f)
                    assertEquals(0, target.generation)
                    repeat(2) { index ->
                        touch(MotionEvent.ACTION_DOWN, x, y)
                        touch(MotionEvent.ACTION_UP, x, y)
                        assertEquals(index + 1, target.generation)
                        assertEquals(if (index == 0) Color.BLUE else Color.GREEN, color())
                    }
                } finally { bitmap.recycle() }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
