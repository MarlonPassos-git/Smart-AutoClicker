package com.buzbuz.smartautoclicker.core.domain.model.action

import android.graphics.Point
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier

/** Spatial distribution used to generate one execution of an [AreaClick]. */
enum class AreaClickDistribution {
    RANDOM,
    DISTRIBUTED,
}

/** Executes a finite sequence of clicks inside a user-confirmed polygon. */
data class AreaClick(
    override val id: Identifier,
    override val eventId: Identifier,
    override val name: String? = null,
    override var priority: Int,
    val vertices: List<Point> = emptyList(),
    val clickCount: Int = DEFAULT_CLICK_COUNT,
    val distribution: AreaClickDistribution = AreaClickDistribution.RANDOM,
    val pressDurationMs: Long = DEFAULT_PRESS_DURATION_MS,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
) : Action() {

    override fun isComplete(): Boolean =
        super.isComplete() && clickCount in CLICK_COUNT_RANGE &&
                pressDurationMs in PRESS_DURATION_RANGE && intervalMs in INTERVAL_RANGE &&
                AreaClickGeometry.isValidPolygon(vertices)

    override fun hashCodeNoIds(): Int =
        listOf(name, vertices, clickCount, distribution, pressDurationMs, intervalMs).hashCode()

    override fun deepCopy(): AreaClick = copy(
        name = "" + name,
        vertices = vertices.map(::Point),
    )

    companion object {
        const val DEFAULT_CLICK_COUNT = 5
        const val DEFAULT_PRESS_DURATION_MS = 1L
        const val DEFAULT_INTERVAL_MS = 100L
        val CLICK_COUNT_RANGE = 1..50
        val PRESS_DURATION_RANGE = 1L..59_999L
        val INTERVAL_RANGE = 0L..3_600_000L
    }
}
