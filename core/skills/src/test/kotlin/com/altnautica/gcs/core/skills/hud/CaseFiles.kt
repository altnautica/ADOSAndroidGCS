package com.altnautica.gcs.core.skills.hud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One golden case shared with the web, Kotlin and Rust ports: `{name, input, expected}`. */
data class GoldenCase(val name: String, val input: JsonObject, val expected: JsonObject)

/** Reads `fixtures/<file>` from the test resources (byte copies of the shared case files). */
fun goldenCases(file: String): List<GoldenCase> {
    val text = requireNotNull(GoldenCase::class.java.classLoader!!.getResource("fixtures/$file")) { "missing fixture $file" }.readText()
    return Json.parseToJsonElement(text).jsonArray.map {
        val o = it.jsonObject
        GoldenCase(o.getValue("name").str()!!, o.getValue("input").jsonObject, o.getValue("expected").jsonObject)
    }
}

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
