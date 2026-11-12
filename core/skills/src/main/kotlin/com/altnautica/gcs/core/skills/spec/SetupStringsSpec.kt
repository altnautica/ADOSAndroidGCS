package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.Serializable

/** Setup wizard strings keyed by their dotted key; `{arg}` placeholders interpolate. */
@Serializable
data class SetupStringsSpec(
    val v: Int,
    val locale: String,
    val strings: Map<String, String>,
) {
    /**
     * The string for [key] with each `{name}` replaced from [args]. A missing body variant
     * (`setup.<step>.body.<variant>`) falls back to the step's `setup.<step>.body`; any other
     * missing key reads as the key itself, so a gap shows rather than a blank.
     */
    fun format(key: String, args: Map<String, String> = emptyMap()): String {
        var text = strings[key] ?: SETUP_BODY_VARIANT.matchEntire(key)?.let { strings[it.groupValues[1]] } ?: return key
        for ((name, value) in args) text = text.replace("{$name}", value)
        return text
    }
}

private val SETUP_BODY_VARIANT = Regex("""^(setup\.[^.]+\.body)\.[^.]+$""")
