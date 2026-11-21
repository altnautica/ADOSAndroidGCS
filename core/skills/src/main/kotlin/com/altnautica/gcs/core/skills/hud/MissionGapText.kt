package com.altnautica.gcs.core.skills.hud

/**
 * Coverage gaps in the operators' shared words, a port of the shared web module's
 * `gapReasonText`: why the pilot skipped a waypoint (`pilot.gap.reason.<reason>`) and the
 * one-line row a mission view lists per gap (`pilot.gap.line`).
 */
class MissionGapText(private val text: SpecText) {

    /** The reason as words; the bare code with spaces for a reason the spec does not word, null without one. */
    fun reason(reason: String?): String? {
        if (reason.isNullOrEmpty()) return null
        val key = "$REASON_PREFIX$reason"
        return if (text.has(key)) text.text(key) else reason.replace('_', ' ')
    }

    /** `WP 4 · Skipped · Inside an obstacle` for waypoint [wp]; without a reason the line ends at "Skipped". */
    fun line(wp: String, reason: String?): String {
        val words = reason(reason) ?: return text.text(LINE_KEY, mapOf("wp" to wp, "reason" to "")).removeSuffix(SEPARATOR)
        return text.text(LINE_KEY, mapOf("wp" to wp, "reason" to words))
    }

    private companion object {
        const val REASON_PREFIX = "pilot.gap.reason."
        const val LINE_KEY = "pilot.gap.line"
        const val SEPARATOR = " · "
    }
}
