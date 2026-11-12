package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.runtime.Immutable
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.skills.hud.PillAlert
import com.altnautica.gcs.core.skills.hud.PillAlertInput
import com.altnautica.gcs.core.skills.hud.PillAlertLevel
import com.altnautica.gcs.core.skills.hud.PillFailsafe
import com.altnautica.gcs.core.skills.hud.PillFcStatus
import com.altnautica.gcs.core.skills.hud.PillSeverity
import com.altnautica.gcs.core.skills.hud.PillSource
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.StatusPill
import com.altnautica.gcs.core.skills.spec.LayoutSpec
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.pilot.PillFacts
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult

/** Status pill severity (`layout.json` `status_pill.severity`). */
enum class StatusSeverity { Ok, Warning, Critical }

/** Where a status item comes from (`status_pill.sources`); the key of its source label. */
enum class StatusSource(val key: String) {
    PilotReadiness("pilot_readiness"),
    FcPrearm("fc_prearm"),
    FcStatusText("fc_status_text"),
    Battery("battery"),
    Link("link"),
    NavSource("nav_source"),
    Storage("storage"),
    Pilot("pilot"),
    Controller("controller"),
    Preflight("preflight"),
}

/**
 * One active warning. [alert] carries a cockpit alert (its title is a string resource) and
 * [check] a failing pre-flight item worded as [specKey] `alert.preflight_failing` with the
 * check's own label; every other item reads [text], already worded by the shared pill.
 */
@Immutable
data class StatusItem(
    val key: String,
    val source: StatusSource,
    val severity: StatusSeverity,
    val alert: CockpitAlert? = null,
    val text: String? = null,
    val check: PreflightItem? = null,
    val specKey: String? = null,
)

/**
 * What the pill shows: the worst severity, the count and whether a new item is unacknowledged.
 * [waiting] holds while neither a pilot frame nor a heartbeat has arrived and nothing is
 * active: the pill then reads the neutral no-link text, never all systems OK.
 */
@Immutable
data class StatusPillUi(val severity: StatusSeverity, val count: Int, val flashing: Boolean, val top: StatusItem?, val waiting: Boolean = false)

object StatusModel {
    /**
     * The flight screen's pill: the shared rules, except that the storage item grades the
     * phone's own volume, not the vehicle's disk, so it warns and never turns critical.
     */
    fun statusPill(text: SpecText, layout: LayoutSpec): StatusPill =
        StatusPill(text, layout.statusPill!!.storageWarnPct!!, storageCritPct = null)

    /**
     * The active items, built by the shared status pill [pill] (`hud/status-pill.ts`): worst
     * first, then in source order. The cockpit's alerts feed it, except the FC's pre-arm reason
     * (its `prearm` source) and the critical FC text alert, whose text is among [texts].
     * [texts] are the FC status texts held for the pill: one the pilot relayed goes through the
     * shared rule's pilot texts, dropped while the pilot state is stale; one heard on the vehicle
     * link stays. The pilot's readiness, the storage use, [facts] (pilot, agent, controller and
     * pre-flight rules) and a fresh pilot's [failsafe] feed the rest.
     */
    fun items(
        alerts: List<CockpitAlert>,
        preflight: PreflightResult,
        texts: List<ReceivedText>,
        facts: PillFacts,
        pill: StatusPill,
        failsafe: PillFailsafe = PillFailsafe.None,
    ): List<StatusItem> {
        val bySpecId = HashMap<String, CockpitAlert>()
        val inputs = ArrayList<PillAlertInput>()
        var prearm: List<String>? = null
        for (a in alerts) {
            when (a.id) {
                AlertId.PREARM -> prearm = listOfNotNull(a.detail)
                AlertId.STATUS_TEXT -> Unit
                else -> {
                    val id = specAlertId(a.id)
                    bySpecId[id] = a
                    inputs += PillAlertInput(id, pillLevel(a.level), "")
                }
            }
        }
        val (relayed, link) = texts.partition { it.relayed }
        val checks = facts.preflightFailing.associateBy { it.id }
        val active = pill.alerts(
            inputs,
            preflight.readiness,
            prearm,
            preflight.storageUsedPct,
            relayed.map { PillFcStatus(it.severity, it.text) },
            pilotRule = facts.pilotRule,
            pilotAgeMs = facts.pilotAgeMs,
            agentReachable = facts.agentReachable,
            controllerLost = facts.controllerLost,
            // The list words each item with its check's own label (see [StatusItem]).
            preflightFailing = facts.preflightFailing.map { it.id to it.id },
            failsafe = failsafe,
            linkLoss = facts.linkLoss,
            linkFcStatus = link.map { PillFcStatus(it.severity, it.text) },
            armUnknown = facts.armUnknown,
            navSource = facts.navSource,
        )
        return active.map { p -> item(p, bySpecId, checks) }
    }

    private fun item(p: PillAlert, alerts: Map<String, CockpitAlert>, checks: Map<String, PreflightItem>): StatusItem {
        val severity = if (p.severity == PillSeverity.Critical) StatusSeverity.Critical else StatusSeverity.Warning
        val alert = p.id.takeIf { it.startsWith(ALERT_PREFIX) }?.let { alerts[it.removePrefix(ALERT_PREFIX)] }
        if (alert != null) return StatusItem(alertKey(p.id, alert), alertSource(alert.id, p.source), severity, alert = alert)
        val check = p.id.takeIf { it.startsWith(PREFLIGHT_PREFIX) }?.let { checks[it.removePrefix(PREFLIGHT_PREFIX)] }
        if (check != null) return StatusItem(p.id, StatusSource.Preflight, severity, check = check, specKey = PREFLIGHT_FAILING_KEY)
        return StatusItem(p.id, statusSource(p.source), severity, text = p.text)
    }

    /** An FC state alert keys on its detail too, so CRITICAL turning EMERGENCY flashes an acknowledged pill. */
    private fun alertKey(id: String, alert: CockpitAlert): String =
        if (alert.id == AlertId.FC_STATE && alert.detail != null) "$id:${alert.detail}" else id

    /** The pill for [items]; it flashes while any item's key is missing from [acknowledged]. [waiting]: see [StatusPillUi]. */
    fun pill(items: List<StatusItem>, acknowledged: Set<String>, waiting: Boolean = false): StatusPillUi = StatusPillUi(
        severity = items.maxOfOrNull { it.severity } ?: StatusSeverity.Ok,
        count = items.size,
        flashing = items.any { it.key !in acknowledged },
        top = items.firstOrNull(),
        waiting = waiting && items.isEmpty(),
    )

    /** The shared alert id (`link_lost`, `batt_low`, ...) of a cockpit alert; Android's own alerts keep theirs. */
    fun specAlertId(alertId: String): String = when (alertId) {
        AlertId.LINK_LOST -> "link_lost"
        AlertId.LINK_STALE -> "link_stale"
        AlertId.BATT_CRIT -> "batt_crit"
        AlertId.BATT_LOW -> "batt_low"
        AlertId.GPS_NO_3D -> "gps_no_3d"
        AlertId.VIDEO_FROZEN -> "video_frozen"
        else -> alertId
    }

    private fun pillLevel(level: AlertLevel): PillAlertLevel = when (level) {
        AlertLevel.Critical -> PillAlertLevel.Critical
        AlertLevel.Warning -> PillAlertLevel.Warning
        AlertLevel.Advisory -> PillAlertLevel.Advisory
    }

    /** The source of an alert: the shared rule, and Android's own alerts where they have a better one. */
    private fun alertSource(alertId: String, shared: PillSource): StatusSource = when (alertId) {
        AlertId.EKF_FAILSAFE -> StatusSource.NavSource
        AlertId.CONTROLLER_BATTERY -> StatusSource.Battery
        AlertId.FC_STATE, AlertId.FENCE_BREACH -> StatusSource.FcStatusText
        else -> statusSource(shared)
    }

    private fun statusSource(s: PillSource): StatusSource = when (s) {
        PillSource.PilotReadiness -> StatusSource.PilotReadiness
        PillSource.FcPrearm -> StatusSource.FcPrearm
        PillSource.FcStatusText -> StatusSource.FcStatusText
        PillSource.Battery -> StatusSource.Battery
        // The spec's `status_pill.sources` has no video label: frozen video lists under the link.
        PillSource.Link, PillSource.Video -> StatusSource.Link
        PillSource.NavSource -> StatusSource.NavSource
        PillSource.Storage -> StatusSource.Storage
        PillSource.Pilot -> StatusSource.Pilot
        PillSource.Controller -> StatusSource.Controller
        PillSource.Preflight -> StatusSource.Preflight
    }

    private const val ALERT_PREFIX = "alert:"
    private const val PREFLIGHT_PREFIX = "preflight:"

    /** The spec text of a failing pre-flight item in the pill (`Pre-flight: {item}`). */
    const val PREFLIGHT_FAILING_KEY = "alert.preflight_failing"

    /** FC status text kept for the pill: WARNING (4) down to ERROR (3); CRITICAL and worse are alerts already. */
    const val MAV_SEVERITY_WARNING = 4

    /** How long a WARNING status text stays in the pill's list. */
    const val WARNING_TEXT_HOLD_MS = 60_000L

    /** At most this many WARNING status texts are kept. */
    const val WARNING_TEXT_MAX = 4
}

/**
 * The bottom row's values (D, H, H.S, V.S, AGL); null renders as a dash, never as zero.
 * [aglTag] is null when AGL is measured above the ground (a rangefinder); otherwise it names
 * the reference the height is really from (`HOME`, `BARO`), shown beside the AGL label.
 */
@Immutable
data class FlightValues(
    val distanceM: Float?,
    val heightM: Float?,
    val hSpeedMs: Float?,
    val vSpeedMs: Float?,
    val aglM: Float?,
    val aglTag: String? = null,
) {
    companion object {
        val Empty = FlightValues(null, null, null, null, null)
    }
}

/** The bottom-left card's pages, switched by swipe or by its dots. */
enum class CardPage { Map, Attitude, Obstacles;

    fun step(delta: Int): CardPage = entries[Math.floorMod(ordinal + delta, entries.size)]
}
