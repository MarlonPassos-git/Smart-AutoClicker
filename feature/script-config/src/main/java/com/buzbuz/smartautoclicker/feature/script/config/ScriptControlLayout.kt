package com.buzbuz.smartautoclicker.feature.script.config

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/** Keeps injected gestures from operating execution controls; used as the root of overlay_script_run.xml. */
class ScriptControlLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    internal var onScriptTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Accessibility gestures use UNKNOWN, while physical fingers/styli retain their tool type.
        // Device ids cannot distinguish them on Android 11; see UserTouchWatcher in core/common/actions.
        if (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onScriptTouch?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
