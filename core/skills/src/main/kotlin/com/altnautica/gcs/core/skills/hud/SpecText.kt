package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.spec.SetupStringsSpec

/**
 * Text from the cockpit spec's string table with single-brace `{arg}` interpolation, the
 * same rules as the shared web module: an unknown key reads as itself, a missing arg fills
 * empty, `mode` (an FC mode name) reads upper case, and enumerated arg values (`source`,
 * `action`, `cause`, `profile`) resolve through their own keys; the profile lookup ignores case.
 */
class SpecText(private val strings: Map<String, String>) {
    constructor(spec: SetupStringsSpec) : this(spec.strings)

    fun has(key: String): Boolean = strings.containsKey(key)

    /** True when [key]'s text has a `{placeholder}`: a template, not a sentence on its own. */
    fun takesArgs(key: String): Boolean = strings[key]?.contains('{') == true

    fun text(key: String, args: Map<String, Any?> = emptyMap()): String {
        val template = strings[key] ?: return key
        return ARG.replace(template) { m ->
            val name = m.groupValues[1]
            val s = args[name]?.toString() ?: return@replace ""
            val prefix = ARG_KEYS[name]
            val lookup = if (name in CASELESS_ARGS) s.lowercase() else s
            when {
                prefix != null && strings.containsKey(prefix + lookup) -> strings.getValue(prefix + lookup)
                name == "mode" -> s.uppercase()
                else -> s
            }
        }
    }

    /** A MAV_RESULT name (`DENIED`, `TEMPORARILY_REJECTED`, …) as the spec's sentence; any other text unchanged. */
    fun mavResultText(message: String?): String? =
        message?.let { m -> "mav_result.$m".takeIf(::has)?.let { text(it) } ?: m }

    private companion object {
        val ARG = Regex("""\{([a-z_]+)\}""")
        val ARG_KEYS = mapOf(
            "source" to "pilot.banner.source.",
            "action" to "pilot.link_loss_action.",
            "cause" to "pilot.banner.cause.",
            "profile" to "pilot.profile.",
        )

        /** The pilot sends the profile upper case (`NORMAL`); its keys are the lower-case wire values. */
        val CASELESS_ARGS = setOf("profile")
    }
}
