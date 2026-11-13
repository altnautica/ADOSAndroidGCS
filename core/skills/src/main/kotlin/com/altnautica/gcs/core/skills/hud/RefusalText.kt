package com.altnautica.gcs.core.skills.hud

/**
 * Readable text for a refusal code the drone answers a pilot action, a motion lease or a
 * setup step with, from the spec's `refusal.*` keys. Client-made codes with spaces map to
 * underscores; `not_ready:<id>` names the readiness item; `fc_refused:<text>` carries the flight
 * controller's own reason for refusing a mode change; an unknown code is shown, never dropped.
 */
fun SpecText.refusalText(code: String?, readinessLabel: ((String) -> String)? = null): String {
    if (code.isNullOrEmpty()) return text("refusal.none")
    if (code.startsWith(NOT_READY)) {
        val id = code.removePrefix(NOT_READY)
        val item = readinessLabel?.invoke(id) ?: if (has("readiness.item.$id")) text("readiness.item.$id") else id
        return text("refusal.not_ready", mapOf("item" to item))
    }
    if (code.startsWith(FC_REFUSED)) {
        val reason = code.removePrefix(FC_REFUSED).trim()
        return if (reason.isEmpty()) text("refusal.fc_refused") else text("refusal.fc_refused_reason", mapOf("reason" to reason))
    }
    // A code naming a template key (`unknown`, a bare `not_ready`) is unknown too: never a prefix with nothing after it.
    val key = "refusal.${code.replace(' ', '_')}"
    if (has(key) && !takesArgs(key)) return text(key)
    if (has("mav_result.$code")) return text("mav_result.$code")
    return text("refusal.unknown", mapOf("code" to code))
}

/** True when the spec names [code] (or it is a `not_ready:` or `fc_refused:` code): a sentence, not a raw id. */
fun SpecText.knowsRefusal(code: String): Boolean =
    code.startsWith(NOT_READY) || code.startsWith(FC_REFUSED) || has("refusal.${code.replace(' ', '_')}")

private const val NOT_READY = "not_ready:"
private const val FC_REFUSED = "fc_refused:"
