package com.altnautica.gcs.feature.configure.params

/** Outcome of checking an edited parameter value before it is written to the vehicle. */
sealed interface ParamValidation {
    data class Valid(val value: Double) : ParamValidation
    data class Invalid(val reason: ParamInvalidReason) : ParamValidation
}

enum class ParamInvalidReason { NotANumber, NotInteger, BelowMin, AboveMax, NotAnOption, UnknownBits, ReadOnly }

object ParamValidator {
    /**
     * Names always treated as read-only, whatever the metadata says: the flight statistics
     * counters and the sensor identity parameters, which describe the fitted hardware.
     */
    private val READ_ONLY_PATTERNS = listOf(Regex("^STAT_"), Regex("^INS_\\w+_ID$"), Regex("^GND_\\w+_ID$"))

    /** Documented read-only but still writable: setting `STAT_RESET` to 0 resets the flight statistics. */
    private val WRITABLE_DESPITE_READONLY = setOf("STAT_RESET")

    /** Whether [name] refuses writes: flagged read-only by its metadata, or matched by name. */
    fun isReadOnly(name: String, meta: ParamMeta?): Boolean = when {
        name in WRITABLE_DESPITE_READONLY -> false
        meta?.readOnly == true -> true
        else -> READ_ONLY_PATTERNS.any { it.containsMatchIn(name) }
    }

    /** Checks free-text input. Without metadata any finite number is accepted. */
    fun validate(name: String, meta: ParamMeta?, input: String): ParamValidation {
        val value = input.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return ParamValidation.Invalid(ParamInvalidReason.NotANumber)
        return validate(name, meta, value)
    }

    fun validate(name: String, meta: ParamMeta?, value: Double): ParamValidation {
        if (isReadOnly(name, meta)) return ParamValidation.Invalid(ParamInvalidReason.ReadOnly)
        if (meta == null) return ParamValidation.Valid(value)
        if (meta.isInteger && value % 1.0 != 0.0) return ParamValidation.Invalid(ParamInvalidReason.NotInteger)
        when (meta.editor) {
            ParamEditorKind.Bitmask -> {
                if (value < 0) return ParamValidation.Invalid(ParamInvalidReason.UnknownBits)
                val known = meta.bits.fold(0L) { acc, b -> acc or (1L shl b.bit) }
                if (value.toLong() and known.inv() != 0L) return ParamValidation.Invalid(ParamInvalidReason.UnknownBits)
                return ParamValidation.Valid(value)
            }
            ParamEditorKind.Enum -> {
                // A documented range widens an enum: the listed values are suggestions.
                if (meta.min == null && meta.max == null && meta.options.none { it.value == value }) {
                    return ParamValidation.Invalid(ParamInvalidReason.NotAnOption)
                }
            }
            ParamEditorKind.Number -> Unit
        }
        meta.min?.let { if (value < it) return ParamValidation.Invalid(ParamInvalidReason.BelowMin) }
        meta.max?.let { if (value > it) return ParamValidation.Invalid(ParamInvalidReason.AboveMax) }
        return ParamValidation.Valid(value)
    }

    /** Toggles [bit] in a bitmask value. */
    fun toggleBit(value: Double, bit: Int): Double = (value.toLong() xor (1L shl bit)).toDouble()

    fun isBitSet(value: Double, bit: Int): Boolean = value.toLong() and (1L shl bit) != 0L
}
