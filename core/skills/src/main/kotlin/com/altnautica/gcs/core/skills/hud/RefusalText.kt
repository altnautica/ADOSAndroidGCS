package com.altnautica.gcs.core.skills.hud

/**
 * Readable text for a refusal code the drone answers a pilot action, a motion lease or a
 * setup step with, from the spec's `refusal.*` keys. Client-made codes with spaces map to
 * underscores; `not_ready:<id>` names the readiness item; an unknown code is shown, never dropped.
 */
fun SpecText.refusalText(code: String?, readinessLabel: ((String) -> String)? = null): String {
    if (code.isNullOrEmpty()) return text("refusal.none")
    if (code.startsWith(NOT_READY)) {
        val id = code.removePrefix(NOT_READY)
        val item = readinessLabel?.invoke(id) ?: if (has("readiness.item.$id")) text("readiness.item.$id") else id
        return text("refusal.not_ready", mapOf("item" to item))
    }
    val key = "refusal.${code.replace(' ', '_')}"
    if (has(key)) return text(key)
    if (has("mav_result.$code")) return text("mav_result.$code")
    return text("refusal.unknown", mapOf("code" to code))
}

/** True when the spec names [code] (or it is a `not_ready:` code): a sentence, not a raw id. */
fun SpecText.knowsRefusal(code: String): Boolean = code.startsWith(NOT_READY) || has("refusal.${code.replace(' ', '_')}")

private const val NOT_READY = "not_ready:"
