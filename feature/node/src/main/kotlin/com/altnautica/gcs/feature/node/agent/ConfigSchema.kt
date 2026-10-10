package com.altnautica.gcs.feature.node.agent

import com.altnautica.gcs.feature.node.common.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Editor kind for one config leaf, derived from its JSON Schema node. */
sealed interface FieldType {
    data object Bool : FieldType
    data class Integer(val min: Long?, val max: Long?) : FieldType
    data class Decimal(val min: Double?, val max: Double?) : FieldType
    data object Text : FieldType
    data class Choice(val options: List<String>) : FieldType

    /** Arrays and free-form objects: edited as JSON text. */
    data object Json : FieldType
}

sealed interface ConfigNode {
    /** Dotted config key, e.g. `video.camera.fps`. */
    val key: String
    val title: String
    val description: String?

    data class Group(
        override val key: String,
        override val title: String,
        override val description: String?,
        val children: List<ConfigNode>,
    ) : ConfigNode

    data class Leaf(
        override val key: String,
        override val title: String,
        override val description: String?,
        val type: FieldType,
        val nullable: Boolean,
        /** `x-secret`: render set / not set, never the value. */
        val secret: Boolean,
    ) : ConfigNode
}

/** The agent config tree parsed from `GET /api/config/schema`. */
class ConfigSchema(val root: ConfigNode.Group) {

    private val leaves: Map<String, ConfigNode.Leaf> = buildMap {
        fun walk(node: ConfigNode) {
            when (node) {
                is ConfigNode.Leaf -> put(node.key, node)
                is ConfigNode.Group -> node.children.forEach(::walk)
            }
        }
        walk(root)
    }

    fun leaf(key: String): ConfigNode.Leaf? = leaves[key]

    /** The group at a dotted [key]; the root for an empty key. */
    fun group(key: String): ConfigNode.Group? {
        if (key.isEmpty()) return root
        var cur: ConfigNode.Group = root
        val path = key.split('.')
        for (i in path.indices) {
            val wanted = path.subList(0, i + 1).joinToString(".")
            cur = cur.children.firstOrNull { it.key == wanted } as? ConfigNode.Group ?: return null
        }
        return cur
    }

    companion object {

        fun parse(schema: JsonObject): ConfigSchema {
            val defs = (schema["\$defs"] as? JsonObject) ?: JsonObject(emptyMap())
            val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val children = props.map { (name, node) -> build(name, name, node as? JsonObject ?: JsonObject(emptyMap()), defs) }
            return ConfigSchema(ConfigNode.Group("", "", null, children))
        }

        private fun build(key: String, name: String, raw: JsonObject, defs: JsonObject): ConfigNode {
            // Only the property's own title names it; a referenced model's title is a type name.
            val title = raw.str("title")?.takeIf { it.isNotBlank() } ?: humanize(name)
            val (node, nullable) = resolve(raw, defs)
            val description = node.str("description")
            val props = node["properties"] as? JsonObject
            if (node.str("type") == "object" && props != null && props.isNotEmpty()) {
                return ConfigNode.Group(
                    key = key,
                    title = title,
                    description = description,
                    children = props.map { (child, n) -> build("$key.$child", child, n as? JsonObject ?: JsonObject(emptyMap()), defs) },
                )
            }
            return ConfigNode.Leaf(
                key = key,
                title = title,
                description = description,
                type = fieldType(node),
                nullable = nullable,
                secret = (node["x-secret"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        /** Follows `$ref`, unwraps `anyOf [T, null]`, and reports whether null is allowed. */
        private fun resolve(raw: JsonObject, defs: JsonObject, depth: Int = 0): Pair<JsonObject, Boolean> {
            if (depth > 16) return raw to false
            raw.str("\$ref")?.let { ref ->
                val target = defs[ref.substringAfterLast('/')] as? JsonObject ?: return raw to false
                val merged = JsonObject(target + raw.filterKeys { it != "\$ref" })
                return resolve(merged, defs, depth + 1)
            }
            val anyOf = raw["anyOf"] as? JsonArray
            if (anyOf != null) {
                val options = anyOf.mapNotNull { it as? JsonObject }
                val nonNull = options.filterNot { it.str("type") == "null" }
                if (nonNull.size == 1) {
                    val (inner, _) = resolve(nonNull[0], defs, depth + 1)
                    val merged = JsonObject(inner + raw.filterKeys { it != "anyOf" })
                    return merged to (options.size > nonNull.size)
                }
            }
            return raw to false
        }

        private fun fieldType(node: JsonObject): FieldType {
            (node["enum"] as? JsonArray)?.let { values ->
                val options = values.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (options.isNotEmpty()) return FieldType.Choice(options)
            }
            return when (node.str("type")) {
                "boolean" -> FieldType.Bool
                "integer" -> FieldType.Integer(node.longBound("minimum"), node.longBound("maximum"))
                "number" -> FieldType.Decimal(node.doubleBound("minimum"), node.doubleBound("maximum"))
                "string" -> FieldType.Text
                else -> FieldType.Json
            }
        }

        private fun JsonObject.longBound(key: String): Long? =
            (this[key] as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.let { bd ->
                // Schemas emit full i64/u64 limits for unbounded fields; those are no bound to an operator.
                if (bd.abs() >= java.math.BigDecimal(UNBOUNDED)) null else bd.toLong()
            }

        private fun JsonObject.doubleBound(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

        private const val UNBOUNDED = 4_294_967_295L

        /** `bitrate_kbps` → `Bitrate kbps`. */
        fun humanize(name: String): String = name.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
}

/** Converts operator input into the JSON value `PUT /api/config` accepts for [leaf]. */
object ConfigValueCodec {

    sealed interface Parsed {
        data class Ok(val value: JsonElement) : Parsed
        data class Invalid(val reason: Reason) : Parsed
    }

    enum class Reason { NotANumber, BelowMinimum, AboveMaximum, NotAnOption, NotJson }

    fun encode(leaf: ConfigNode.Leaf, input: String): Parsed {
        val text = input.trim()
        if (text.isEmpty() && leaf.nullable && leaf.type !is FieldType.Text) return Parsed.Ok(JsonNull)
        return when (val t = leaf.type) {
            FieldType.Bool -> when (text.lowercase()) {
                "true" -> Parsed.Ok(JsonPrimitive(true))
                "false" -> Parsed.Ok(JsonPrimitive(false))
                else -> Parsed.Invalid(Reason.NotAnOption)
            }
            is FieldType.Integer -> {
                val v = text.toLongOrNull() ?: return Parsed.Invalid(Reason.NotANumber)
                when {
                    t.min != null && v < t.min -> Parsed.Invalid(Reason.BelowMinimum)
                    t.max != null && v > t.max -> Parsed.Invalid(Reason.AboveMaximum)
                    else -> Parsed.Ok(JsonPrimitive(v))
                }
            }
            is FieldType.Decimal -> {
                val v = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Parsed.Invalid(Reason.NotANumber)
                when {
                    t.min != null && v < t.min -> Parsed.Invalid(Reason.BelowMinimum)
                    t.max != null && v > t.max -> Parsed.Invalid(Reason.AboveMaximum)
                    else -> Parsed.Ok(JsonPrimitive(v))
                }
            }
            is FieldType.Choice -> if (text in t.options) Parsed.Ok(JsonPrimitive(text)) else Parsed.Invalid(Reason.NotAnOption)
            FieldType.Text -> if (text.isEmpty() && leaf.nullable) Parsed.Ok(JsonNull) else Parsed.Ok(JsonPrimitive(input))
            FieldType.Json -> runCatching { Json.parseToJsonElement(text) }
                .fold({ Parsed.Ok(it) }, { Parsed.Invalid(Reason.NotJson) })
        }
    }

    /** The editable text for a current value; secrets never echo. */
    fun display(leaf: ConfigNode.Leaf, value: JsonElement?): String = when {
        leaf.secret -> ""
        value == null || value is JsonNull -> ""
        value is JsonPrimitive -> value.content
        else -> value.toString()
    }

    /** A secret reads back as `***` when it holds a value. */
    fun secretIsSet(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.let { it !is JsonNull && it.content.isNotEmpty() } == true

    fun boolValue(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.let { it !is JsonNull && it.jsonPrimitive.booleanOrNull == true } == true
}
