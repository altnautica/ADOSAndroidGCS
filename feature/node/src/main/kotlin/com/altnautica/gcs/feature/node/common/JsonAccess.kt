package com.altnautica.gcs.feature.node.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Null-safe reads of agent JSON. Agent bodies vary by version and profile, so
 * every read tolerates an absent key, a null, or an unexpected type.
 */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    ?: (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.toLong()

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

internal fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

/** The value at a dotted [path] (`video.camera.fps`), or null when any segment is missing. */
internal fun JsonObject.at(path: String): JsonElement? {
    var cur: JsonElement = this
    for (segment in path.split('.')) {
        cur = (cur as? JsonObject)?.get(segment) ?: return null
    }
    return cur
}
