package com.buzbuz.smartautoclicker.core.script.packages

import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenRegion
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenSize
import kotlinx.serialization.json.*

/** Publishes named calibration results without mutating a saved or executing revision. */
class ScriptPackageCalibration(private val repository: ScriptPackageRepository) {
    /** Saves a region and optional cropped PNG, e.g. `save(id, "confirm", size, area, png)`. */
    fun save(storageId: String, name: String, size: ScreenSize, area: ScreenRegion, png: ByteArray? = null): StoredScriptPackage {
        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}"))) {
            "resource.name=$name; expected an identifier of 1..64 characters"
        }
        val manifest = ScriptManifest.parse(repository.readFile(storageId, "manifest.json").toString(Charsets.UTF_8))
        val previous = manifest.document["screen"] as? JsonObject
        if (previous != null) require(previous["width"]?.jsonPrimitive?.int == size.width && previous["height"]?.jsonPrimitive?.int == size.height) {
            "screen=${size.width}x${size.height}; expected the existing calibration ${previous["width"]}x${previous["height"]}; use the same display resolution and orientation"
        }
        val region = buildJsonObject { put("left", area.left); put("top", area.top); put("right", area.right); put("bottom", area.bottom) }
        val regions = previous?.get("regions")?.jsonObject ?: JsonObject(emptyMap())
        val screen = JsonObject((previous ?: JsonObject(emptyMap())) + mapOf(
            "width" to JsonPrimitive(size.width), "height" to JsonPrimitive(size.height),
            "regions" to JsonObject(regions + (name to region)),
        ))
        val changes = mutableMapOf<String, ByteArray>()
        val resources = if (png == null) manifest.resources else {
            val path = "images/$name.png"
            changes[path] = png
            JsonObject(manifest.resources + (name to buildJsonObject {
                put("type", "image"); put("path", path); put("width", area.right - area.left); put("height", area.bottom - area.top)
            }))
        }
        changes["manifest.json"] = JsonObject(manifest.document + mapOf("screen" to screen, "resources" to resources)).toString().toByteArray()
        return repository.saveRevision(storageId, changes)
    }
}
