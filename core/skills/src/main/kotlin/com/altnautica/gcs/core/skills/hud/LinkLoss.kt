package com.altnautica.gcs.core.skills.hud

import kotlin.math.ceil

/** The text of a link-loss action (`rtl`/`rth` → "Return home", `land`, `hold`/`loiter`), or a dash. */
fun SpecText.linkLossActionText(action: String?): String {
    val key = "pilot.link_loss_action.${action.orEmpty()}"
    return if (!action.isNullOrEmpty() && has(key)) text(key) else "—"
}

/** The link-loss countdown line, or null with no countdown running. */
fun SpecText.linkLossLine(linkLossInS: Float?, linkLossAction: String?): String? {
    if (linkLossInS == null) return null
    return text(
        "flow.link_loss.countdown",
        mapOf("action" to linkLossActionText(linkLossAction), "in_s" to ceil(linkLossInS.toDouble()).toInt()),
    )
}
