package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity

/** Alert levels; an advisory never reaches the pill. */
enum class PillAlertLevel { Critical, Warning, Advisory }

/** One alert from a surface's alert rules; [id] is the spec key suffix (`link_lost`, `batt_low`, ...). */
data class PillAlertInput(val id: String, val level: PillAlertLevel, val title: String)

/** One FC status text at WARNING or worse: MAVLink [severity] (0 emergency … 7 debug). */
data class PillFcStatus(val severity: Int, val text: String)

/** The FC flying a failsafe RTL or land of its own (`fc_failsafe`), and why (`failsafe_cause`). */
data class PillFailsafe(val active: Boolean, val cause: String?) {
    companion object {
        val None = PillFailsafe(false, null)

        /** The pill's failsafe inputs from a pilot state (`pilotFailsafe`). */
        fun of(p: PilotState?): PillFailsafe = PillFailsafe(p?.fcFailsafe == true, p?.failsafeCause)
    }
}

/** The pilot counting down to its link-loss [action] (`link_loss_in_s`, `link_loss_action`). */
data class PillLinkLoss(val inS: Float, val action: String?) {
    companion object {
        /** The countdown a pilot state reports, or null with none running. */
        fun of(p: PilotState?): PillLinkLoss? =
            p?.linkLossInS?.let { PillLinkLoss(it, p.linkLossAction ?: p.banner?.args?.get("action")) }
    }
}

/**
 * The resolved navigation source as the nav-source chip shows it: its [kind] and [tone]. A
 * warn or crit tone raises one `nav_source_degraded` item (`alert.nav_source_degraded`).
 */
data class PillNavSource(val kind: Kind, val tone: Tone) {
    /** The source kind; [textKey] is its `nav_source.*` word, null for a stale source. */
    enum class Kind(val wire: String, val textKey: String?) {
        Gps("gps", "nav_source.gps"),
        Ext("ext", "nav_source.ext_nav"),
        Flow("flow", "nav_source.flow"),
        Dr("dr", "nav_source.dr"),
        Stale("stale", null),
    }

    enum class Tone(val wire: String) { Good("good"), Warn("warn"), Crit("crit"), Muted("muted") }
}

enum class PillSource(val wire: String) {
    PilotReadiness("pilot_readiness"),
    FcPrearm("fc_prearm"),
    FcStatusText("fc_status_text"),
    Battery("battery"),
    Link("link"),
    NavSource("nav_source"),
    Storage("storage"),
    Video("video"),
    Pilot("pilot"),
    Controller("controller"),
    Preflight("preflight"),
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
 * source order, and the pill's face. [storageCritPct] null keeps the storage item a warning.
 */
class StatusPill(
    private val text: SpecText,
    private val storageWarnPct: Int,
    private val storageCritPct: Int?,
    private val staleAfterMs: Long = PilotState.STALE_MS,
) {

    /**
     * Every active pill alert. [prearm] is null while unknown; [storagePct] null when unknown.
     *
     * [pilotRule] turns the pilot rule on for a surface that reads the pilot kind. Then
     * [pilotAgeMs] is the newest pilot state's age, null when none ever arrived: at or past the
     * spec's stale age (or null) the pill shows one `pilot_not_answering` warning and drops the
     * readiness, FC status-text and controller items that came from that stale frame.
     * [agentReachable] false is a critical link item; [controllerLost] (see [controllerLost])
     * a warning; each [preflightFailing] item (`id` to its label, passed only on the ground) a
     * warning, none while [armUnknown] (the arm state is unknown, so the drone may be airborne).
     * A degraded [navSource] (warn or crit tone) is one `nav_source_degraded` item of that severity.
     * An active [failsafe] from a fresh pilot state is one critical `failsafe` item, and a
     * running [linkLoss] countdown from one a critical `link_loss` item worded as the
     * `pilot.banner.link_loss` banner, so the pill never reads all well while the banner counts.
     *
     * [linkFcStatus] are FC texts the surface hears on its own vehicle link rather than from the
     * pilot: graded like [fcStatus] but kept while the pilot state is stale.
     */
    fun alerts(
        alerts: List<PillAlertInput>,
        readiness: List<ReadinessItem>,
        prearm: List<String>?,
        storagePct: Double?,
        fcStatus: List<PillFcStatus> = emptyList(),
        pilotRule: Boolean = false,
        pilotAgeMs: Long? = null,
        agentReachable: Boolean = true,
        controllerLost: Boolean = false,
        preflightFailing: List<Pair<String, String>> = emptyList(),
        failsafe: PillFailsafe = PillFailsafe.None,
        linkLoss: PillLinkLoss? = null,
        linkFcStatus: List<PillFcStatus> = emptyList(),
        armUnknown: Boolean = false,
        navSource: PillNavSource? = null,
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
        if (!agentReachable) {
            out += PillAlert("agent_unreachable", PillSource.Link, PillSeverity.Critical, text.text("alert.agent_unreachable"))
        }
        val pilotStale = pilotRule && (pilotAgeMs == null || pilotAgeMs >= staleAfterMs)
        if (pilotStale) {
            out += PillAlert("pilot_not_answering", PillSource.Pilot, PillSeverity.Warning, text.text("alert.pilot_not_answering"))
        } else {
            if (failsafe.active) out += PillAlert("failsafe", PillSource.Pilot, PillSeverity.Critical, failsafeText(failsafe.cause))
            if (linkLoss != null) out += PillAlert("link_loss", PillSource.Pilot, PillSeverity.Critical, linkLossText(linkLoss))
        }
        for (r in if (pilotStale) emptyList() else readiness) {
            if (r.ok) continue
            // A blocking item refuses take-off: critical, as the pre-flight sheet fails it.
            val sev = if (r.severity == ReadinessSeverity.Block) PillSeverity.Critical else PillSeverity.Warning
            out += PillAlert("pilot:${r.id}", PillSource.PilotReadiness, sev, r.msg.ifEmpty { r.id })
        }
        if (controllerLost && !pilotStale) {
            out += PillAlert("controller_lost", PillSource.Controller, PillSeverity.Warning, text.text("alert.controller_lost"))
        }
        // Pre-flight items are a ground surface: hidden while the arm state is unknown.
        for ((id, label) in if (armUnknown) emptyList() else preflightFailing) {
            out += PillAlert("preflight:$id", PillSource.Preflight, PillSeverity.Warning, text.text("alert.preflight_failing", mapOf("item" to label)))
        }
        val navKey = navSource?.kind?.textKey
        if (navSource != null && navKey != null && (navSource.tone == PillNavSource.Tone.Warn || navSource.tone == PillNavSource.Tone.Crit)) {
            out += PillAlert(
                "nav_source_degraded",
                PillSource.NavSource,
                if (navSource.tone == PillNavSource.Tone.Crit) PillSeverity.Critical else PillSeverity.Warning,
                text.text("alert.nav_source_degraded", mapOf("source" to text.text(navKey))),
            )
        }
        if (!prearm.isNullOrEmpty()) {
            val t = prearm.last().replace(PREARM, "")
            out += PillAlert("fc:$t", PillSource.FcPrearm, PillSeverity.Warning, text.text("flow.preflight.fc_prearm.failing", mapOf("text" to t)))
        }
        // The FC repeats a warning while its condition lasts: one alert per text, at its worst severity.
        val fcAt = HashMap<String, Int>()
        for (s in if (pilotStale) linkFcStatus else fcStatus + linkFcStatus) {
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
                if (storageCritPct != null && storagePct >= storageCritPct) PillSeverity.Critical else PillSeverity.Warning,
                text.text("cockpit.status.storage_used", mapOf("pct" to jsRound(storagePct))),
            )
        }
        return out.sortedBy { if (it.severity == PillSeverity.Critical) 0 else 1 }
    }

    /** `Failsafe: <cause>`, the cause worded by `pilot.banner.cause.*`; an unknown cause reads as itself. */
    fun failsafeText(cause: String?): String {
        val c = cause?.ifEmpty { null } ?: "fc"
        val key = "pilot.banner.cause.$c"
        return text.text("alert.failsafe", mapOf("cause" to if (text.has(key)) text.text(key) else c))
    }

    /** `Link lost: <action> in <s> s`, the seconds rounded up as the banner shows them. */
    fun linkLossText(l: PillLinkLoss): String = text.text(
        "pilot.banner.link_loss",
        mapOf("action" to text.linkLossActionText(l.action), "in_s" to kotlin.math.ceil(l.inS.toDouble()).toInt()),
    )

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

        /**
         * The pilot holds for a lost controller while armed and airborne (`status_pill.controller_rule`).
         * An unknown arm state is possibly airborne.
         */
        fun controllerLost(p: PilotState?): Boolean =
            p != null && p.holdReason == "controller-lost" && p.possiblyAirborne

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
