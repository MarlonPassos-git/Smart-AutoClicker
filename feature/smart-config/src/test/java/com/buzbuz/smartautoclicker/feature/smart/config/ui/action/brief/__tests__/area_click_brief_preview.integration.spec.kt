package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.brief.__tests__

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.ItemBriefView
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers.AreaClickDescription
import com.buzbuz.smartautoclicker.feature.smart.config.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.buzbuz.smartautoclicker.core.common.overlays.R as OverlaysR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AreaClickBriefPreviewIntegrationSpec {

    @Test
    fun `area preview draws polygon over target screen coordinates`() {
        val preview = inflatePreview()
        preview.setDescription(
            AreaClickDescription(listOf(PointF(100f, 100f), PointF(300f, 100f), PointF(300f, 300f), PointF(100f, 300f))),
            animate = false,
        )

        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        preview.draw(Canvas(bitmap))

        assertTrue(Color.alpha(bitmap.getPixel(200, 200)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(20, 20)))
    }

    private fun inflatePreview(): ItemBriefView {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(application, R.style.ScenarioConfigTheme)
        val root = LayoutInflater.from(context).inflate(OverlaysR.layout.overlay_position_selection_view, null)
        return root.findViewById<ItemBriefView>(OverlaysR.id.position_selector).apply {
            measure(exactly(400), exactly(400))
            layout(0, 0, 400, 400)
        }
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
