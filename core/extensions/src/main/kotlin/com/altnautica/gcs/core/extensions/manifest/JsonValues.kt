package com.altnautica.gcs.core.extensions.manifest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Lenient readers for manifest JSON. Manifests are forward-compatible: a field of the
 * wrong shape reads as absent, never throws.
 */
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

/** A string, or a number/boolean rendered as a string. */
internal fun JsonElement?.str(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

/** A finite number, or a numeric string. */
internal fun JsonElement?.num(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val d = if (p.isString) p.content.trim().toDoubleOrNull() else p.doubleOrNull
    return d?.takeIf { it.isFinite() }
}

/** Only a real JSON boolean. */
internal fun JsonElement?.bool(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    return p.booleanOrNull
}

/** A scalar (string, number or boolean) primitive, else null. */
internal fun JsonElement?.scalar(): JsonPrimitive? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return p
    if (p.booleanOrNull != null) return p
    return p.takeIf { it.doubleOrNull?.isFinite() == true }
}

/** The entry's stable id, accepting either `id` or `key`. */
internal fun JsonObject.readId(): String? = this["id"].str() ?: this["key"].str()

/** First present value among snake_case and camelCase spellings. */
internal fun JsonObject.either(snake: String, camel: String): JsonElement? = this[snake] ?: this[camel]
