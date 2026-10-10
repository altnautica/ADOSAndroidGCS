package com.altnautica.gcs.core.extensions.parameters

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** The JSON-Schema scalar types the parameter subset supports. */
enum class ParameterType(val wire: String) {
    Number("number"),
    Integer("integer"),
    Boolean("boolean"),
    Str("string"),
    ;

    companion object {
        fun fromWire(v: String?): ParameterType? = entries.firstOrNull { it.wire == v }
    }
}

/** The control a parameter renders as. */
enum class ParameterWidget(val wire: String) {
    Number("number"),
    Range("range"),
    Boolean("boolean"),
    Enum("enum"),
    Bitmask("bitmask"),
    Str("string"),
    Model("model"),
    ModelUpload("model_upload"),
    Camera("camera"),
    Group("group"),
    ;

    companion object {
        fun fromWire(v: String?): ParameterWidget? = entries.firstOrNull { it.wire == v }
    }
}

/** Where a parameter's committed value is written. */
enum class ParameterBinding(val wire: String) {
    PluginConfig("plugin.config"),
    EngineDetector("engine.detector"),
    AgentConfig("agent.config"),
    ;

    companion object {
        fun fromWire(v: String?): ParameterBinding? = entries.firstOrNull { it.wire == v }
    }
}

/** A Draft-07 subset describing one parameter's value. Scalars are JSON primitives. */
data class ParameterSchema(
    val type: ParameterType,
    val minimum: Double? = null,
    val maximum: Double? = null,
    /** UI quantization hint; never a validation rule. */
    val step: Double? = null,
    val enum: List<JsonPrimitive>? = null,
    val pattern: String? = null,
    val default: JsonPrimitive? = null,
)

data class VisibleIf(val key: String, val equals: JsonPrimitive)

data class BitLabel(val bit: Int, val label: String)

data class ParameterUi(
    val widget: ParameterWidget? = null,
    val label: String? = null,
    val group: String? = null,
    val help: String? = null,
    val visibleIf: VisibleIf? = null,
    val task: String? = null,
    val order: Double? = null,
    val bits: List<BitLabel>? = null,
    /** Camera widget: roster purpose listed first. Stored value is a camera id or `auto`. */
    val purpose: String? = null,
)

data class PluginParameter(
    val key: String,
    val schema: ParameterSchema,
    val ui: ParameterUi? = null,
    val binding: ParameterBinding = ParameterBinding.PluginConfig,
)

/** Result of a schema or value check; [error] is set when not ok. */
data class Validation(val ok: Boolean, val error: String? = null) {
    companion object {
        val Ok = Validation(true)
        fun fail(error: String) = Validation(false, error)
    }
}

object ParameterRules {

    /** Structural and domain checks applied when a manifest is parsed. */
    fun validateSchema(s: ParameterSchema): Validation {
        if (s.minimum != null && s.maximum != null && s.minimum > s.maximum) {
            return Validation.fail("schema.minimum must be <= schema.maximum")
        }
        if (s.step != null && s.step <= 0) return Validation.fail("schema.step must be a positive number")
        if (s.enum != null && s.enum.isEmpty()) return Validation.fail("schema.enum must be a non-empty array")
        if (s.pattern != null && runCatching { Regex(s.pattern) }.isFailure) {
            return Validation.fail("schema.pattern is not a valid regex")
        }
        if (s.default != null) {
            val dv = validateValue(s, s.default)
            if (!dv.ok) return Validation.fail("schema.default ${dv.error}")
        }
        return Validation.Ok
    }

    /**
     * Validate a committed value. A declared `enum` is the whole rule (the value must equal
     * one entry, type included); otherwise type, bounds and pattern apply. `step` never
     * rejects a value.
     */
    fun validateValue(s: ParameterSchema, value: JsonPrimitive?): Validation {
        if (value == null) return Validation.fail("is required")
        if (s.enum != null) {
            return if (s.enum.any { sameScalar(it, value) }) {
                Validation.Ok
            } else {
                Validation.fail("is not one of the allowed values")
            }
        }
        return when (s.type) {
            ParameterType.Boolean ->
                if (!value.isString && value.booleanOrNull != null) Validation.Ok else Validation.fail("must be boolean")
            ParameterType.Str -> {
                if (!value.isString) return Validation.fail("must be string")
                if (s.pattern != null && !Regex(s.pattern).containsMatchIn(value.content)) {
                    Validation.fail("must match pattern \"${s.pattern}\"")
                } else {
                    Validation.Ok
                }
            }
            ParameterType.Number, ParameterType.Integer -> {
                val d = numberOf(value) ?: return Validation.fail("must be ${s.type.wire}")
                if (s.type == ParameterType.Integer && d != Math.floor(d)) return Validation.fail("must be integer")
                if (s.minimum != null && d < s.minimum) return Validation.fail("must be >= ${fmt(s.minimum)}")
                if (s.maximum != null && d > s.maximum) return Validation.fail("must be <= ${fmt(s.maximum)}")
                Validation.Ok
            }
        }
    }

    /** Clamp to bounds and quantize to `step` for numbers; other scalars pass through. */
    fun clamp(s: ParameterSchema, value: JsonPrimitive): JsonPrimitive {
        if (s.type != ParameterType.Number && s.type != ParameterType.Integer) return value
        var v = numberOf(value) ?: return value
        s.minimum?.let { v = max(v, it) }
        s.maximum?.let { v = min(v, it) }
        if (s.step != null && s.step > 0) {
            val base = s.minimum ?: 0.0
            v = base + Math.round((v - base) / s.step) * s.step
            s.minimum?.let { v = max(v, it) }
            s.maximum?.let { v = min(v, it) }
        }
        return if (s.type == ParameterType.Integer) JsonPrimitive(v.roundToLong()) else JsonPrimitive(v)
    }

    /** The rendered control when `ui.widget` is omitted. */
    fun inferWidget(s: ParameterSchema, ui: ParameterUi?): ParameterWidget {
        ui?.widget?.let { return it }
        if (!s.enum.isNullOrEmpty()) return ParameterWidget.Enum
        return when (s.type) {
            ParameterType.Boolean -> ParameterWidget.Boolean
            ParameterType.Number, ParameterType.Integer -> ParameterWidget.Number
            ParameterType.Str -> ParameterWidget.Str
        }
    }

    /** The declared default, else a type-appropriate empty value. */
    fun defaultFor(s: ParameterSchema): JsonPrimitive {
        s.default?.let { return it }
        s.enum?.firstOrNull()?.let { return it }
        return when (s.type) {
            ParameterType.Boolean -> JsonPrimitive(false)
            ParameterType.Number, ParameterType.Integer -> JsonPrimitive(s.minimum ?: 0.0)
            ParameterType.Str -> JsonPrimitive("")
        }
    }

    /** `visible_if`: shown only when the referenced key's current value equals the target. */
    fun isVisible(p: PluginParameter, values: Map<String, JsonPrimitive>): Boolean {
        val cond = p.ui?.visibleIf ?: return true
        val current = values[cond.key] ?: return false
        return sameScalar(current, cond.equals)
    }

    /** Scalar equality with JS `===` semantics (type and value). */
    fun sameScalar(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a.isString != b.isString) return false
        if (a.isString) return a.content == b.content
        val ab = a.booleanOrNull
        val bb = b.booleanOrNull
        if (ab != null || bb != null) return ab == bb
        return a.doubleOrNull != null && a.doubleOrNull == b.doubleOrNull
    }

    private fun numberOf(p: JsonPrimitive): Double? {
        if (p.isString || p.booleanOrNull != null) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
