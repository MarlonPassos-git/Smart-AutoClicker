package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.gesture.bindings

import android.graphics.Bitmap
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.ui.utils.createColorIndicatorDrawable
import com.buzbuz.smartautoclicker.core.ui.views.clickoffset.ClickOffsetView
import com.buzbuz.smartautoclicker.feature.smart.config.R
import kotlinx.coroutines.Job

/** Preserves the condition preview while editing an offset through the shared gesture dialog. */
internal fun ClickOffsetView.bindConditionPreview(
    condition: ScreenCondition?,
    bitmapProvider: ((ScreenCondition.Image, (Bitmap?) -> Unit) -> Job)?,
): Job? {
    when (condition) {
        is ScreenCondition.Image -> {
            setImageResource(R.drawable.ic_image_condition_big)
            return bitmapProvider?.invoke(condition) { bitmap ->
                if (bitmap != null) setImageBitmap(bitmap)
            }
        }
        is ScreenCondition.Color -> setImageDrawable(context.createColorIndicatorDrawable(condition.color))
        is ScreenCondition.Number -> setImageResource(R.drawable.ic_number_condition)
        is ScreenCondition.Text -> setImageResource(R.drawable.ic_text_condition)
        null -> setImageResource(R.drawable.ic_image_condition_big)
    }
    return null
}
