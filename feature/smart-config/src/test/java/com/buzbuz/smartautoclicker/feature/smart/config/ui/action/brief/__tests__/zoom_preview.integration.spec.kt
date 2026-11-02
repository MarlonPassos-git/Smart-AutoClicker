package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.brief.__tests__

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.ItemBriefDescription
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.ItemBriefView
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers.AreaClickDescription
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers.ZoomDescription
import com.buzbuz.smartautoclicker.feature.smart.config.R
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.buzbuz.smartautoclicker.core.common.overlays.R as OverlaysR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomPreviewIntegrationSpec {

    @Test
    fun `zoom preview keeps its configured center after gesture renderer migration`() {
        val zoomIn = render(ZoomDescription(ZoomDirection.IN, PointF(200f, 200f), 100))
        val zoomOut = render(ZoomDescription(ZoomDirection.OUT, PointF(200f, 200f), 100))

        val empty = render(ZoomDescription(center = null))
        val moved = render(ZoomDescription(center = PointF(160f, 200f), intensityPx = 100))
        assertFalse(zoomIn.sameAs(empty))
        assertFalse(zoomIn.sameAs(moved))

        saveEvidence("zoom-in.png", zoomIn)
        assertFalse(zoomOut.sameAs(empty))
        saveEvidence("polygon.png", render(AreaClickDescription(
            listOf(PointF(60f, 80f), PointF(340f, 60f), PointF(310f, 330f), PointF(90f, 300f)),
        )))
    }

    private fun render(description: ItemBriefDescription): Bitmap {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(application, R.style.ScenarioConfigTheme)
        val root = LayoutInflater.from(context).inflate(OverlaysR.layout.overlay_position_selection_view, null)
        val preview = root.findViewById<ItemBriefView>(OverlaysR.id.position_selector)
        val spec = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        preview.measure(spec, spec)
        preview.layout(0, 0, 400, 400)
        preview.setDescription(description, animate = false)
        return Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { preview.draw(Canvas(this)) }
    }

    private fun saveEvidence(name: String, bitmap: Bitmap) {
        val directory = File("build/outputs/upstream-sync").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
