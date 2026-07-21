package com.buzbuz.smartautoclicker.feature.smart.config.ui.common.model.action

import android.content.Context
import androidx.annotation.DrawableRes
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClick
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.feature.smart.config.R

@DrawableRes
internal fun getAreaClickIconRes(): Int = R.drawable.ic_area_click

internal fun AreaClick.getDescription(context: Context, inError: Boolean): String {
    if (inError) return context.getString(R.string.item_error_action_invalid_generic)
    val distributionText = when (distribution) {
        AreaClickDistribution.RANDOM -> context.getString(R.string.field_area_click_random)
        AreaClickDistribution.DISTRIBUTED -> context.getString(R.string.field_area_click_distributed)
    }
    return context.getString(R.string.item_area_click_details, clickCount, distributionText, intervalMs)
}
