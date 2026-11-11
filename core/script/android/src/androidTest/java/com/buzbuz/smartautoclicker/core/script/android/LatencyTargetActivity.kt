package com.buzbuz.smartautoclicker.core.script.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONObject

/** Owned target for manual capture/accessibility latency checks; exists only in the instrumentation APK. */
class LatencyTargetActivity : Activity() {
    internal lateinit var target: LatencyTargetView
    internal var recoveryDialog: AlertDialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        target = LatencyTargetView(this)
        setContentView(target)
        // Opt-in setup for the generic recovery example; latency runs keep their original target.
        if (intent.getBooleanExtra("recoveryDialog", false)) {
            recoveryDialog = AlertDialog.Builder(this)
                .setTitle("Diálogo de teste")
                .setMessage("Feche este diálogo para liberar o retângulo de teste.")
                .setPositiveButton("Continuar", null)
                .setCancelable(false)
                .show()
        }
    }
}

internal class LatencyTargetView(context: Context) : View(context) {
    private val paint = Paint()
    private val button = RectF()
    private var lastDrawnGeneration = -1
    private var touchInProgress = false
    var generation = 0
        private set

    init {
        contentDescription = "Alvo de teste: toque no retângulo para alternar a cor"
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(36, 22, 61))
        paint.color = Color.WHITE
        paint.textSize = 22 * resources.displayMetrics.scaledDensity
        canvas.drawText("Alvo de teste de latência", 24f, 100f, paint)
        canvas.drawText("Toques confirmados: $generation", 24f, 160f, paint)
        button.set(width * .2f, height * .7f, width * .8f, height * .9f)
        paint.color = if (generation % 2 == 0) Color.GREEN else Color.BLUE
        canvas.drawRect(button, paint)
        if (lastDrawnGeneration != generation) {
            lastDrawnGeneration = generation
            record("draw")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchInProgress = button.contains(event.x, event.y)
                if (touchInProgress) record("down", event)
            }
            MotionEvent.ACTION_UP -> {
                val completed = touchInProgress && button.contains(event.x, event.y)
                val handled = touchInProgress
                touchInProgress = false
                if (completed) {
                    record("up", event)
                    generation++
                    performClick()
                    invalidate()
                }
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchInProgress) record("cancel", event)
                touchInProgress = false
            }
        }
        return touchInProgress
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun record(phase: String, event: MotionEvent? = null) {
        val report = JSONObject().put("kind", "latency_target").put("phase", phase)
            .put("generation", generation).put("monotonicNanos", System.nanoTime())
            .put("width", width).put("height", height)
        event?.let { report.put("eventUptimeMs", it.eventTime).put("x", it.x).put("y", it.y) }
        Log.i("KlickrLatencyTarget", report.toString())
    }
}
