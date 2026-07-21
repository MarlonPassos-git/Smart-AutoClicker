/* Copyright (C) 2026 Kevin Buzeau */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.common.model.action

import android.content.Context
import androidx.annotation.DrawableRes
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.domain.model.action.Zoom
import com.buzbuz.smartautoclicker.core.ui.utils.formatDuration
import com.buzbuz.smartautoclicker.feature.smart.config.R

@DrawableRes
internal fun getZoomIconRes(): Int = R.drawable.ic_hint_pinch

internal fun Zoom.getDescription(context: Context, inError: Boolean): String = when {
    inError -> context.getString(R.string.item_error_action_invalid_generic)
    else -> context.getString(
        R.string.zoom_details,
        context.getString(if (direction == ZoomDirection.IN) R.string.zoom_direction_in else R.string.zoom_direction_out),
        intensityPx ?: 0,
        formatDuration(zoomDurationMs ?: 1),
    )
}
