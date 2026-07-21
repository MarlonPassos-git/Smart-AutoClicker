package com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.ItemBriefDescription
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.ItemBriefRenderer

internal class AreaClickBriefRenderer(
    briefView: View,
    viewStyle: AreaClickBriefRendererStyle,
) : ItemBriefRenderer<AreaClickBriefRendererStyle>(briefView, viewStyle) {

    private var vertices = emptyList<PointF>()

    override fun onNewDescription(description: ItemBriefDescription, animate: Boolean) {
        val areaClick = description as? AreaClickDescription ?: return
        vertices = areaClick.vertices.map { vertex -> PointF(vertex.x, vertex.y) }
    }

    override fun onInvalidate() = Unit

    override fun onDraw(canvas: Canvas) {
        if (vertices.size < 3) return
        val polygonPath = vertices.toPolygonPath()
        canvas.drawPath(polygonPath, viewStyle.fillPaint)
        canvas.drawPath(polygonPath, viewStyle.outlinePaint)
        vertices.forEach { vertex ->
            canvas.drawCircle(vertex.x, vertex.y, viewStyle.vertexRadiusPx, viewStyle.vertexPaint)
        }
    }

    override fun onStop() {
        vertices = emptyList()
    }

    private fun List<PointF>.toPolygonPath(): Path = Path().apply {
        moveTo(first().x, first().y)
        drop(1).forEach { point -> lineTo(point.x, point.y) }
        close()
    }
}

data class AreaClickDescription(
    val vertices: List<PointF>,
) : ItemBriefDescription

internal data class AreaClickBriefRendererStyle(
    val fillPaint: Paint,
    val outlinePaint: Paint,
    val vertexPaint: Paint,
    val vertexRadiusPx: Float,
)
