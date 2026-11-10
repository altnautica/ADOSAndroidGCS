package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity

/** Alert levels; an advisory never reaches the pill. */
enum class PillAlertLevel { Critical, Warning, Advisory }

/** One alert from a surface's alert rules; [id] is the spec key suffix (`link_lost`, `batt_low`, ...). */
data class PillAlertInput(val id: String, val level: PillAlertLevel, val title: String)

/** One FC status text at WARNING or worse: MAVLink [severity] (0 emergency … 7 debug). */
data class PillFcStatus(val severity: Int, val text: String)

enum class PillSource(val wire: String) {
    PilotReadiness("pilot_readiness"),
    FcPrearm("fc_prearm"),
    FcStatusText("fc_status_text"),
    Battery("battery"),
    Link("link"),
    NavSource("nav_source"),
    Storage("storage"),
    Video("video"),
}

enum class PillSeverity(val wire: String) { Ok("ok"), Warning("warning"), Critical("critical") }

data class PillAlert(val id: String, val source: PillSource, val severity: PillSeverity, val text: String)

data class PillSummary(
    val severity: PillSeverity,
    val text: String,
    val count: Int,
    /** An alert not yet acknowledged is active. */
    val flashing: Boolean,
    val alerts: List<PillAlert>,
)

/**
 * The top bar's status pill (`layout.json` `status_pill`), a port of the shared web module
 * `hud/status-pill.ts` held to the same golden cases: every active alert, worst first, then in
 * source order, and the pill's face.
 */
class StatusPill(private val text: SpecText, private val storageWarnPct: Int, private val storageCritPct: Int) {

    /** Every active pill alert. [prearm] is null while unknown; [storagePct] null when unknown. */
    fun alerts(
        alerts: List<PillAlertInput>,
        readiness: List<ReadinessItem>,
        prearm: List<String>?,
        storagePct: Double?,
        fcStatus: List<PillFcStatus> = emptyList(),
    ): List<PillAlert> {
        val out = mutableListOf<PillAlert>()
        for (a in alerts) {
            val sev = when (a.level) {
                PillAlertLevel.Advisory -> continue
                PillAlertLevel.Critical -> PillSeverity.Critical
                PillAlertLevel.Warning -> PillSeverity.Warning
            }
            out += PillAlert("alert:${a.id}", alertSource(a.id), sev, a.title)
        }
        for (r in readiness) {
            if (r.ok) continue
            // A blocking item refuses take-off: critical, as the pre-flight sheet fails it.
            val sev = if (r.severity == ReadinessSeverity.Block) PillSeverity.Critical else PillSeverity.Warning
            out += PillAlert("pilot:${r.id}", PillSource.PilotReadiness, sev, r.msg.ifEmpty { r.id })
        }
        if (!prearm.isNullOrEmpty()) {
            val t = prearm.last().replace(PREARM, "")
            out += PillAlert("fc:$t", PillSource.FcPrearm, PillSeverity.Warning, text.text("flow.preflight.fc_prearm.failing", mapOf("text" to t)))
        }
        // The FC repeats a warning while its condition lasts: one alert per text, at its worst severity.
        val fcAt = HashMap<String, Int>()
        for (s in fcStatus) {
            if (PREARM.containsMatchIn(s.text)) continue
            val sev = if (s.severity <= 2) PillSeverity.Critical else PillSeverity.Warning
            val at = fcAt[s.text]
            if (at == null) {
                fcAt[s.text] = out.size
                out += PillAlert("fcst:${s.text}", PillSource.FcStatusText, sev, s.text)
            } else if (sev == PillSeverity.Critical) {
                out[at] = out[at].copy(severity = PillSeverity.Critical)
            }
        }
        if (storagePct != null && storagePct >= storageWarnPct) {
            out += PillAlert(
                "storage",
                PillSource.Storage,
                if (storagePct >= storageCritPct) PillSeverity.Critical else PillSeverity.Warning,
                "${text.text("cockpit.status.source.storage")} ${jsRound(storagePct)}%",
            )
        }
        return out.sortedBy { if (it.severity == PillSeverity.Critical) 0 else 1 }
    }

    /** The pill's face for the active [alerts] and the acknowledged ids. */
    fun summary(alerts: List<PillAlert>, acked: Set<String>): PillSummary {
        val count = alerts.size
        val severity = when {
            count == 0 -> PillSeverity.Ok
            alerts.any { it.severity == PillSeverity.Critical } -> PillSeverity.Critical
            else -> PillSeverity.Warning
        }
        val face = when (count) {
            0 -> text.text("cockpit.status.ok")
            1 -> alerts[0].text.ifEmpty { text.text("cockpit.status.one") }
            else -> text.text("cockpit.status.many", mapOf("n" to count))
        }
        return PillSummary(severity, face, count, alerts.any { it.id !in acked }, alerts)
    }

    companion object {
        private val PREARM = Regex("""^(PreArm|Arm):\s*""")

        /** The pill source of an alert id. */
        fun alertSource(id: String): PillSource = when {
            id == "batt_crit" || id == "batt_low" || id.startsWith("battery_pack_") -> PillSource.Battery
            id == "gps_no_3d" -> PillSource.NavSource
            id == "video_frozen" -> PillSource.Video
            else -> PillSource.Link
        }

        /** JavaScript `Math.round`: halves round up. */
        private fun jsRound(v: Double): Int = kotlin.math.floor(v + 0.5).toInt()
    }
}
