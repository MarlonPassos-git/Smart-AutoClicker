package com.buzbuz.smartautoclicker.core.script.runtime.api

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

/** Named coordinates calibrated at a reference resolution. Orientation/aspect changes require calibration. */
class ScreenProfile private constructor(
    val width: Int,
    val height: Int,
    private val regions: Map<String, ScreenRegion>,
    private val positions: Map<String, ScreenPoint>,
) {
    /** Scales a profile to a matching observation, e.g. after changing the display resolution. */
    fun resolve(size: ScreenSize): JsonObject {
        val ratio = size.width.toDouble() * height / (size.height.toDouble() * width)
        require(size.width > 0 && size.height > 0 && abs(ratio - 1) <= 0.01) {
            "screen=${size.width}x${size.height}; expected the calibrated aspect ratio ${width}x$height (tolerance 1%); recalibrate the profile"
        }
        val scaleX = size.width.toDouble() / width
        val scaleY = size.height.toDouble() / height
        return buildJsonObject {
            put("width", size.width); put("height", size.height)
            put("referenceWidth", width); put("referenceHeight", height)
            put("regions", buildJsonObject {
                regions.forEach { (name, area) -> put(name, buildJsonObject {
                    put("left", floor(area.left * scaleX).toInt())
                    put("top", floor(area.top * scaleY).toInt())
                    put("right", ceil(area.right * scaleX).toInt().coerceAtMost(size.width))
                    put("bottom", ceil(area.bottom * scaleY).toInt().coerceAtMost(size.height))
                }) }
            })
            put("positions", buildJsonObject {
                positions.forEach { (name, point) -> put(name, buildJsonObject {
                    put("x", (point.x * scaleX).roundToInt().coerceIn(0, size.width - 1))
                    put("y", (point.y * scaleY).roundToInt().coerceIn(0, size.height - 1))
                }) }
            })
        }
    }

    companion object {
        /** Validates a package's optional screen profile without executing code or decoding images. */
        fun parse(value: JsonElement): ScreenProfile {
            val profile = value as? JsonObject ?: throw IllegalArgumentException("screen=$value; expected a reference profile object")
            fun JsonObject.integer(field: String, path: String): Int = (get(field) as? JsonPrimitive)
                ?.takeUnless { it.isString }?.intOrNull
                ?: throw IllegalArgumentException("$path.$field=${get(field)}; expected an integer")
            val width = profile.integer("width", "screen")
            val height = profile.integer("height", "screen")
            require(width in 1..10000 && height in 1..10000) { "screen=${width}x$height; expected width and height in 1..10000" }
            fun definitions(field: String): Map<String, JsonObject> {
                val values = profile[field] ?: return emptyMap()
                require(values is JsonObject && values.size <= 256) { "screen.$field=$values; expected at most 256 named objects" }
                return values.mapValues { (name, definition) ->
                    require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}"))) {
                        "screen.$field.name=$name; expected an identifier of 1..64 characters"
                    }
                    definition as? JsonObject ?: throw IllegalArgumentException("screen.$field.$name=$definition; expected an object")
                }
            }
            val regions = definitions("regions").mapValues { (name, region) ->
                val path = "screen.regions.$name"
                val area = ScreenRegion(region.integer("left", path), region.integer("top", path), region.integer("right", path), region.integer("bottom", path))
                require(area.left >= 0 && area.top >= 0 && area.right > area.left && area.bottom > area.top && area.right <= width && area.bottom <= height) {
                    "$path=$region; expected a nonempty region inside ${width}x$height"
                }
                area
            }
            val positions = definitions("positions").mapValues { (name, position) ->
                val path = "screen.positions.$name"
                val point = ScreenPoint(position.integer("x", path), position.integer("y", path))
                require(point.x in 0 until width && point.y in 0 until height) {
                    "$path=$position; expected a position inside ${width}x$height"
                }
                point
            }
            return ScreenProfile(width, height, regions, positions)
        }
    }
}
