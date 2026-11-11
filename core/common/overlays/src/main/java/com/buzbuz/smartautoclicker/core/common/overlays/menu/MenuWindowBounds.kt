package com.buzbuz.smartautoclicker.core.common.overlays.menu

import android.graphics.Point
import android.view.WindowManager

/** Keeps a measured menu reachable when its saved position or window size changes. */
internal fun WindowManager.LayoutParams.fitMenuOnScreen(displaySize: Point) {
    x = x.coerceIn(0, (displaySize.x - width.coerceAtLeast(0)).coerceAtLeast(0))
    y = y.coerceIn(0, (displaySize.y - height.coerceAtLeast(0)).coerceAtLeast(0))
}
