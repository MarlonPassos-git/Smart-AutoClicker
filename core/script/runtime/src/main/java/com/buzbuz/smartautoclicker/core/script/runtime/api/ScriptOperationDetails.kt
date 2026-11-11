package com.buzbuz.smartautoclicker.core.script.runtime.api

import kotlinx.serialization.json.*

/** Projects bounded diagnostic metadata; never retains code, captured pixels or parameter answers. */
internal fun operationDetails(operation: String, request: JsonObject, response: JsonObject?): JsonObject = buildJsonObject {
    fun JsonObjectBuilder.primitive(name: String, source: JsonObject) {
        val value = source[name] as? JsonPrimitive ?: return
        put(name, if (value.content.length > 128) JsonPrimitive(value.content.take(128)) else value)
    }
    for (field in listOf("frame", "kind", "reference", "durationMs", "x", "y")) primitive(field, request)
    (request["region"] as? JsonObject)?.let { region ->
        put("region", buildJsonObject { for (field in listOf("left", "top", "right", "bottom")) this.primitive(field, region) })
    }
    if (operation == "parameters") {
        put("parameterCount", (response?.get("values") as? JsonObject)?.size ?: 0)
        return@buildJsonObject
    }
    if (operation == "profile") {
        response?.let {
            primitive("width", it); primitive("height", it)
            put("regions", (it["regions"] as? JsonObject)?.size ?: 0)
            put("positions", (it["positions"] as? JsonObject)?.size ?: 0)
        }
        return@buildJsonObject
    }
    if (operation == "tapSequence") put("steps", (request["steps"] as? JsonArray)?.size ?: 0)
    if (operation == "gesture") put("fingers", (request["fingers"] as? JsonArray)?.size ?: 0)
    response?.let {
        for (field in listOf("frame", "status", "number", "confidence", "x", "y", "width", "height", "resumeGeneration")) primitive(field, it)
    }
    val results = response?.get("results") as? JsonArray ?: return@buildJsonObject
    put("reads", results.size)
    for (status in listOf("found", "not_found", "invalid_reading")) {
        put(status, results.count { (it as? JsonObject)?.get("status")?.jsonPrimitive?.content == status })
    }
    val queries = request["queries"] as? JsonArray
    put("numbers", JsonArray(results.mapIndexedNotNull { index, value ->
        val result = value as? JsonObject ?: return@mapIndexedNotNull null
        val number = result["number"] as? JsonPrimitive ?: return@mapIndexedNotNull null
        buildJsonObject {
            put("index", index); put("number", number)
            ((queries?.getOrNull(index) as? JsonObject)?.get("reference") as? JsonPrimitive)?.let {
                put("reference", it.content.take(128))
            }
        }
    }.take(8)))
}
