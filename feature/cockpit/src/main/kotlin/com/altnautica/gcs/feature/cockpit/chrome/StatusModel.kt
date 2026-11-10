package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.runtime.Immutable
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.hud.pilotFresh as isPilotFresh
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
 * One active warning. [alert] carries a cockpit alert (its title is a string resource);
 * otherwise [text] is the item's own text (a readiness message or an FC status text).
 * [check] is a failing pre-flight check, named by the check's own label. [specKey] names a
 * cockpit-spec string for items the spec words itself (pilot not answering, agent unreachable,
 * controller lost, a failing pre-flight item as `alert.preflight_failing`).
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

/** What the pill shows: the worst severity, the count and whether a new item is unacknowledged. */
@Immutable
data class StatusPillUi(val severity: StatusSeverity, val count: Int, val flashing: Boolean, val top: StatusItem?)

object StatusModel {
    /**
     * The active items from every source, worst first, then newest first. Cockpit alerts
     * cover the link, battery, FC pre-arm and critical FC text; the pilot's readiness items,
     * the navigation-source and storage checks, and FC status text at WARNING severity add to them.
     *
     * [facts] adds the spec's pilot, agent, controller and pre-flight rules: a pilot state that
     * is missing or stale raises one "Pilot not answering" item and drops the readiness, FC
     * status-text and controller items that came from it, so the pill never reads OK without
     * a fresh pilot state. FC status texts stay: on Android they also arrive over the
     * direct FC link, which the pilot's age says nothing about.
     */
    fun items(
        alerts: List<CockpitAlert>,
        preflight: PreflightResult,
        warningTexts: List<ReceivedText>,
        facts: PillFacts = PillFacts(),
    ): List<StatusItem> {
        val out = ArrayList<StatusItem>()
        val pilotStale = facts.pilotRule && (facts.pilotAgeMs == null || !isPilotFresh(0L, facts.pilotAgeMs))
        if (!facts.agentReachable) {
            out += StatusItem("agent_unreachable", StatusSource.Link, StatusSeverity.Critical, specKey = "alert.agent_unreachable")
        }
        if (pilotStale) {
            out += StatusItem("pilot_not_answering", StatusSource.Pilot, StatusSeverity.Warning, specKey = "alert.pilot_not_answering")
        }
        if (facts.controllerLost && !pilotStale) {
            out += StatusItem("controller_lost", StatusSource.Controller, StatusSeverity.Warning, specKey = "alert.controller_lost")
        }
        for (a in alerts) {
            val severity = when (a.level) {
                AlertLevel.Critical -> StatusSeverity.Critical
                AlertLevel.Warning -> StatusSeverity.Warning
                // FC pre-arm text is an advisory alert, but it is why arming fails: the pill shows it.
                AlertLevel.Advisory -> if (a.id == AlertId.PREARM) StatusSeverity.Warning else continue
            }
            out += StatusItem("alert:${a.id}:${a.detail.orEmpty()}", sourceOf(a.id), severity, alert = a)
        }
        for (r in if (pilotStale) emptyList() else preflight.readiness) {
            if (r.ok) continue
            val severity = if (r.severity == ReadinessSeverity.Block) StatusSeverity.Critical else StatusSeverity.Warning
            out += StatusItem("ready:${r.id}", StatusSource.PilotReadiness, severity, text = r.msg)
        }
        for (c in preflight.checks) {
            if (c.passed || !c.known) continue
            val source = when (c.item) {
                PreflightItem.NavSource -> StatusSource.NavSource
                PreflightItem.Storage -> StatusSource.Storage
                else -> continue
            }
            out += StatusItem("check:${c.item.name}", source, StatusSeverity.Warning, check = c.item)
        }
        for (item in facts.preflightFailing) {
            // Navigation source and storage already have their own items above.
            if (item == PreflightItem.NavSource || item == PreflightItem.Storage) continue
            out += StatusItem("preflight:${item.name}", StatusSource.Preflight, StatusSeverity.Warning, check = item, specKey = PREFLIGHT_FAILING_KEY)
        }
        // Status texts also arrive over the direct FC link, so a stale pilot does not drop them.
        for (t in warningTexts) {
            out += StatusItem("text:${t.text}", StatusSource.FcStatusText, StatusSeverity.Warning, text = t.text)
        }
        out.sortWith(compareByDescending<StatusItem> { it.severity.ordinal }.thenByDescending { it.alert?.sinceMs ?: 0L })
        return out
    }

    /** The pill for [items]; it flashes while any item's key is missing from [acknowledged]. */
    fun pill(items: List<StatusItem>, acknowledged: Set<String>): StatusPillUi = StatusPillUi(
        severity = items.maxOfOrNull { it.severity } ?: StatusSeverity.Ok,
        count = items.size,
        flashing = items.any { it.key !in acknowledged },
        top = items.firstOrNull(),
    )

    private fun sourceOf(alertId: String): StatusSource = when (alertId) {
        AlertId.LINK_LOST, AlertId.LINK_STALE, AlertId.RC_FAILSAFE, AlertId.VIDEO_FROZEN -> StatusSource.Link
        AlertId.BATT_CRIT, AlertId.BATT_LOW, AlertId.CONTROLLER_BATTERY -> StatusSource.Battery
        AlertId.PREARM -> StatusSource.FcPrearm
        AlertId.GPS_NO_3D, AlertId.EKF_FAILSAFE -> StatusSource.NavSource
        else -> StatusSource.FcStatusText
    }

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
