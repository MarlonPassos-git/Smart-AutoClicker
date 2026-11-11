package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import com.buzbuz.smartautoclicker.core.detection.NumberFormatType
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.packages.StoredScriptPackage
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenSize
import kotlinx.serialization.json.*

/** Resolves named resources from one immutable revision; owns decoded templates, never captured frames. */
class PackageScreenResources(
    private val repository: ScriptPackageRepository,
    private val stored: StoredScriptPackage,
    private val maxTemplateBytes: Long = 64L * 1024 * 1024,
) : AutoCloseable {
    private val cached = mutableMapOf<String, AndroidScriptResource>()
    private var closed = false
    private var templateBytes = 0L

    init {
        require(maxTemplateBytes > 0) { "maxTemplateBytes=$maxTemplateBytes; expected a positive byte limit" }
    }

    /** Resolves/caches a declared resource, e.g. `resources.resolve("confirmButton")`. */
    fun resolve(name: String): AndroidScriptResource {
        check(!closed) { "Script resources have been closed" }
        return cached.getOrPut(name) {
            val definition = stored.manifest.resources[name] as? JsonObject
                ?: throw IllegalArgumentException("resource=$name; expected a declared package resource")
            when (val type = definition.getValue("type").jsonPrimitive.content) {
                "image" -> {
                    val bytes = repository.readFile(stored.storageId, definition.getValue("path").jsonPrimitive.content)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) {
                        "resource=$name; expected a decodable image up to 16 million pixels"
                    }
                    val estimatedBytes = bounds.outWidth.toLong() * bounds.outHeight * 4
                    require(estimatedBytes <= maxTemplateBytes - templateBytes) {
                        "resource=$name decodedBytes=$estimatedBytes; expected total templates within $maxTemplateBytes bytes (used=$templateBytes)"
                    }
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
                        "resource=$name; expected a decodable PNG image"
                    }
                    val allocated = bitmap.allocationByteCount.toLong()
                    if (allocated > maxTemplateBytes - templateBytes) {
                        bitmap.recycle()
                        throw IllegalArgumentException("resource=$name decodedBytes=$allocated; expected total templates within $maxTemplateBytes bytes (used=$templateBytes)")
                    }
                    templateBytes += allocated
                    AndroidScriptResource.Image(bitmap, definition.getValue("width").jsonPrimitive.int,
                        definition.getValue("height").jsonPrimitive.int,
                        (stored.manifest.document["screen"] as? JsonObject)?.let { screen ->
                            ScreenSize(screen.getValue("width").jsonPrimitive.int, screen.getValue("height").jsonPrimitive.int)
                        })
                }
                "text" -> AndroidScriptResource.Text(definition.getValue("alternatives").jsonArray.map { it.jsonPrimitive.content },
                    definition.getValue("model").jsonPrimitive.content)
                "color" -> AndroidScriptResource.Color(definition.getValue("argb").jsonPrimitive.int)
                "number" -> AndroidScriptResource.Number(NumberFormatType.valueOf(definition.getValue("format").jsonPrimitive.content))
                else -> throw IllegalArgumentException("resource=$name type=$type; expected image, text, color or number")
            }
        }
    }

    /** Releases templates after the detector has stopped using them. Example: `resources.close()`. */
    override fun close() {
        if (closed) return
        closed = true
        cached.values.filterIsInstance<AndroidScriptResource.Image>().forEach { it.bitmap.recycle() }
        cached.clear()
        templateBytes = 0
    }
}
