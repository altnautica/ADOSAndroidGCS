package com.altnautica.gcs.feature.configure.params

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One labelled value of an enumerated parameter. */
data class ParamOption(val value: Double, val label: String)

/** One named bit of a bitmask parameter. */
data class ParamBit(val bit: Int, val label: String)

/** Documentation for one flight-controller parameter. */
data class ParamMeta(
    val name: String,
    val humanName: String,
    val description: String,
    val units: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val increment: Double? = null,
    val options: List<ParamOption> = emptyList(),
    val bits: List<ParamBit> = emptyList(),
    val rebootRequired: Boolean = false,
    val readOnly: Boolean = false,
    val advanced: Boolean = false,
    val group: String,
    val defaultValue: Double? = null,
    val valueType: String? = null,
    val decimalPlaces: Int? = null,
) {
    /** True when only whole numbers make sense for this parameter. */
    val isInteger: Boolean
        get() = bits.isNotEmpty() ||
            valueType?.let { it.startsWith("int") || it.startsWith("uint") } == true ||
            (options.isNotEmpty() && options.all { it.value % 1.0 == 0.0 } && min == null)

    val editor: ParamEditorKind
        get() = when {
            bits.isNotEmpty() -> ParamEditorKind.Bitmask
            options.isNotEmpty() -> ParamEditorKind.Enum
            else -> ParamEditorKind.Number
        }
}

enum class ParamEditorKind { Number, Enum, Bitmask }

data class ParamMetadataSet(
    val firmware: String,
    val version: String?,
    val params: Map<String, ParamMeta>,
) {
    operator fun get(name: String): ParamMeta? = params[name]
}

/** Parses the bundled parameter documentation files (`param-metadata/<firmware>.json.gz`). */
object ParamMetadataParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ParamMetadataSet {
        val root = json.parseToJsonElement(text).jsonObject
        val provenance = root["provenance"] as? JsonObject
        val firmware = provenance?.get("firmware")?.jsonPrimitive?.content.orEmpty()
        val version = provenance?.get("version")?.jsonPrimitive?.content
        val params = root["params"]?.jsonArray.orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let(::parseParam)
        }
        return ParamMetadataSet(firmware, version, params.associateBy { it.name })
    }

    private fun parseParam(o: JsonObject): ParamMeta? {
        val name = o.string("name") ?: return null
        val range = o["range"] as? JsonObject
        return ParamMeta(
            name = name,
            humanName = o.string("humanName").orEmpty().ifBlank { name },
            description = o.string("description").orEmpty(),
            units = o.string("units")?.takeIf { it.isNotBlank() },
            min = range?.get("min")?.jsonPrimitive?.doubleOrNull,
            max = range?.get("max")?.jsonPrimitive?.doubleOrNull,
            increment = o["increment"]?.jsonPrimitive?.doubleOrNull,
            options = (o["values"] as? JsonArray).orEmpty().mapNotNull { pair ->
                val p = pair as? JsonArray ?: return@mapNotNull null
                val v = p.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                ParamOption(v, p.getOrNull(1)?.jsonPrimitive?.content.orEmpty())
            },
            bits = (o["bitmask"] as? JsonArray).orEmpty().mapNotNull { pair ->
                val p = pair as? JsonArray ?: return@mapNotNull null
                val b = p.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                ParamBit(b, p.getOrNull(1)?.jsonPrimitive?.content.orEmpty())
            },
            rebootRequired = o.bool("rebootRequired"),
            readOnly = o.bool("readOnly"),
            advanced = o.bool("advanced"),
            group = o.string("group")?.takeIf { it.isNotBlank() } ?: groupFromName(name),
            defaultValue = o["defaultValue"]?.jsonPrimitive?.doubleOrNull,
            valueType = o.string("valueType"),
            decimalPlaces = o["decimalPlaces"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** ArduPilot names group by prefix: `BATT_CAPACITY` belongs to `BATT`. */
    fun groupFromName(name: String): String {
        val cut = name.indexOf('_')
        return if (cut > 0) name.substring(0, cut) else name
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
}
