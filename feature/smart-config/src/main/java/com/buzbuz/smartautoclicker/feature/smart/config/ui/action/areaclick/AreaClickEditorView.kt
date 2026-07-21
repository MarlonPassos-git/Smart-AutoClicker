package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickGeometry
import kotlin.math.hypot

class AreaClickEditorView @JvmOverloads constructor(
    context: Context, attributes: AttributeSet? = null,
) : View(context, attributes) {
    private val vertices = mutableListOf<Point>()
    private var preview = emptyList<Point>()
    private var selectedVertex = 0
    private var dragMode = DragMode.NONE
    private var previousX = 0f
    private var previousY = 0f
    private val density = resources.displayMetrics.density
    private val polygonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 3f * density }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * density; textAlign = Paint.Align.CENTER }
    var onValidityChanged: ((Boolean) -> Unit)? = null

    val isValid: Boolean get() = AreaClickGeometry.isValidPolygon(vertices)

    fun setVertices(confirmed: List<Point>) {
        val screenOffset = screenOffset()
        vertices.clear()
        vertices += confirmed.map { point -> Point(point.x - screenOffset.x, point.y - screenOffset.y) }
        if (vertices.isEmpty() && width > 0) createDefaultRectangle()
        selectedVertex = 0
        regeneratePreview()
    }

    fun getVertices(): List<Point> {
        val screenOffset = screenOffset()
        return vertices.map { point -> Point(point.x + screenOffset.x, point.y + screenOffset.y) }
    }

    fun addVertex() {
        if (vertices.size >= 8) return
        val next = (selectedVertex + 1) % vertices.size
        val first = vertices[selectedVertex]
        val second = vertices[next]
        vertices.add(next, Point((first.x + second.x) / 2, (first.y + second.y) / 2))
        selectedVertex = next
        regeneratePreview()
    }

    fun removeVertex() {
        if (vertices.size <= 3) return
        vertices.removeAt(selectedVertex)
        selectedVertex = selectedVertex.coerceAtMost(vertices.lastIndex)
        regeneratePreview()
    }

    fun regeneratePreview() {
        preview = if (isValid) AreaClickGeometry.sample(vertices, PREVIEW_COUNT, AreaClickDistribution.RANDOM) else emptyList()
        onValidityChanged?.invoke(isValid)
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        if (vertices.isEmpty()) createDefaultRectangle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val valid = isValid
        polygonPaint.color = if (valid) Color.argb(70, 40, 150, 240) else Color.argb(90, 230, 40, 40)
        polygonPaint.strokeWidth = 3f * density
        canvas.drawPath(buildPath(), polygonPaint)
        drawVertices(canvas, valid)
        drawPreview(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginDrag(event.x, event.y)
            MotionEvent.ACTION_MOVE -> moveDrag(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDrag()
        }
        return true
    }

    private fun createDefaultRectangle() {
        val rectangleWidth = 230f * density
        val rectangleHeight = 130f * density
        val left = (width - rectangleWidth) / 2f
        val top = (height - rectangleHeight) / 2f
        vertices += listOf(
            Point(left.toInt(), top.toInt()), Point((left + rectangleWidth).toInt(), top.toInt()),
            Point((left + rectangleWidth).toInt(), (top + rectangleHeight).toInt()),
            Point(left.toInt(), (top + rectangleHeight).toInt()),
        )
        regeneratePreview()
    }

    private fun buildPath(): Path = Path().apply {
        vertices.firstOrNull()?.let { moveTo(it.x.toFloat(), it.y.toFloat()) }
        vertices.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        close()
    }

    private fun drawVertices(canvas: Canvas, valid: Boolean) {
        vertices.forEachIndexed { index, point ->
            pointPaint.color = if (!valid) Color.RED else if (index == selectedVertex) Color.YELLOW else Color.WHITE
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 8f * density, pointPaint)
        }
    }

    private fun drawPreview(canvas: Canvas) {
        pointPaint.color = Color.BLACK
        preview.forEachIndexed { index, point ->
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 10f * density, pointPaint)
            pointPaint.color = Color.WHITE
            canvas.drawText("${index + 1}", point.x.toFloat(), point.y + 5f * density, pointPaint)
            pointPaint.color = Color.BLACK
        }
    }

    private fun beginDrag(x: Float, y: Float) {
        previousX = x
        previousY = y
        val nearest = nearestVertex(x, y)
        dragMode = when {
            nearest.second <= TOUCH_RADIUS_DP * density -> DragMode.VERTEX.also { selectedVertex = nearest.first }
            nearestEdge(x, y).second <= TOUCH_RADIUS_DP * density -> DragMode.EDGE.also { selectedVertex = nearestEdge(x, y).first }
            AreaClickGeometry.containsStrictly(vertices, Point(x.toInt(), y.toInt())) -> DragMode.POLYGON
            else -> DragMode.NONE
        }
        invalidate()
    }

    private fun moveDrag(x: Float, y: Float) {
        val deltaX = (x - previousX).toInt()
        val deltaY = (y - previousY).toInt()
        when (dragMode) {
            DragMode.VERTEX -> moveIndices(listOf(selectedVertex), deltaX, deltaY)
            DragMode.EDGE -> moveIndices(listOf(selectedVertex, (selectedVertex + 1) % vertices.size), deltaX, deltaY)
            DragMode.POLYGON -> moveIndices(vertices.indices.toList(), deltaX, deltaY)
            DragMode.NONE -> Unit
        }
        previousX = x
        previousY = y
        preview = emptyList()
        invalidate()
    }

    private fun moveIndices(indices: List<Int>, deltaX: Int, deltaY: Int) {
        val adjustedX = clampDelta(indices, deltaX, width) { it.x }
        val adjustedY = clampDelta(indices, deltaY, height) { it.y }
        indices.forEach { index -> vertices[index].offset(adjustedX, adjustedY) }
    }

    private fun clampDelta(indices: List<Int>, delta: Int, maximum: Int, coordinate: (Point) -> Int): Int {
        val minimumCoordinate = indices.minOf { coordinate(vertices[it]) }
        val maximumCoordinate = indices.maxOf { coordinate(vertices[it]) }
        return delta.coerceIn(-minimumCoordinate, maximum - maximumCoordinate)
    }

    private fun finishDrag() {
        dragMode = DragMode.NONE
        regeneratePreview()
    }

    private fun nearestVertex(x: Float, y: Float): Pair<Int, Float> = vertices.indices
        .map { it to hypot(vertices[it].x - x, vertices[it].y - y) }
        .minBy { it.second }

    private fun nearestEdge(x: Float, y: Float): Pair<Int, Float> = vertices.indices
        .map { index -> index to pointSegmentDistance(x, y, vertices[index], vertices[(index + 1) % vertices.size]) }
        .minBy { it.second }

    private fun pointSegmentDistance(x: Float, y: Float, start: Point, end: Point): Float {
        val dx = (end.x - start.x).toFloat()
        val dy = (end.y - start.y).toFloat()
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(x - start.x, y - start.y)
        val projection = (((x - start.x) * dx + (y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(x - (start.x + projection * dx), y - (start.y + projection * dy))
    }

    private fun screenOffset(): Point {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Point(location[0], location[1])
    }

    private enum class DragMode { NONE, VERTEX, EDGE, POLYGON }

    private companion object {
        const val PREVIEW_COUNT = 5
        const val TOUCH_RADIUS_DP = 24
    }
}
