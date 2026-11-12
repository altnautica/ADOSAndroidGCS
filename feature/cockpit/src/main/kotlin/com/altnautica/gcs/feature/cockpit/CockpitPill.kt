package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.hud.PillFailsafe
import com.altnautica.gcs.core.skills.hud.StatusPill
import com.altnautica.gcs.core.skills.hud.pilotFresh
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.chrome.StatusItem
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import com.altnautica.gcs.feature.cockpit.pilot.CockpitPilot
import com.altnautica.gcs.feature.cockpit.pilot.PillFacts
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.Flow
import com.altnautica.gcs.core.skills.hud.PillLinkLoss
import com.altnautica.gcs.core.skills.hud.PillNavSource

/**
 * The status pill's flows: its spec inputs beyond alerts and readiness ([facts]: the pilot,
 * agent, controller and pre-flight rules) and its [items], re-checked on the 2 Hz tick.
 */
internal class CockpitPill(
    scope: CoroutineScope,
    private val clock: MonotonicClock,
    pilot: CockpitPilot,
    preflight: StateFlow<PreflightResult>,
    alerts: StateFlow<List<CockpitAlert>>,
    criticalText: StateFlow<ReceivedText?>,
    warningTexts: StateFlow<List<ReceivedText>>,
    tick500: StateFlow<Long>,
    pill: StatusPill,
    /** True once the vehicle link has carried a heartbeat. */
    heartbeatSeen: Flow<Boolean>,
    /** The autonomy nav-source chip, null without one. */
    navSource: Flow<PillNavSource?>,
    /** Whether the vehicle is armed: from the pilot while its frame is fresh, else the heartbeat; null when unknown. */
    armed: () -> Boolean?,
) {
    /**
     * True until either a pilot frame or an FC heartbeat has arrived: nothing is known yet, so
     * the pill reads the neutral no-link text rather than all systems OK.
     */
    val waiting: StateFlow<Boolean> = combine(pilot.state, heartbeatSeen) { p, hb -> p == null && !hb }
        .distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), true)

    val facts: StateFlow<PillFacts> = combine(
        combine(pilot.link, pilot.linked) { link, linked -> link to linked },
        pilot.state, preflight, navSource, tick500,
    ) { (link, linked), p, pre, nav, _ ->
        val now = clock.nowMs()
        val fresh = pilotFresh(p?.receivedAtMs, now)
        val arm = armed()
        PillFacts(
            pilotRule = link != null,
            pilotAgeMs = p?.let { now - it.receivedAtMs },
            agentReachable = link == null || linked || fresh,
            controllerLost = StatusPill.controllerLost(p),
            preflightFailing = if (preflightShown(arm)) pre.failing.map { it.item } else emptyList(),
            linkLoss = if (fresh) PillLinkLoss.of(p) else null,
            armUnknown = arm == null,
            navSource = nav,
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), PillFacts())

    /** Alerts, pilot readiness, failing checks, storage, FC texts (from the link and the pilot's relay) and the FC failsafe. */
    val items: StateFlow<List<StatusItem>> = combine(
        combine(alerts, preflight, facts) { a, pre, f -> Triple(a, pre, f) },
        combine(criticalText, warningTexts) { c, w -> c to w },
        pilot.state,
        tick500,
    ) { (a, pre, f), (critical, warnings), p, _ ->
        val now = clock.nowMs()
        val failsafe = if (pilotFresh(p?.receivedAtMs, now)) PillFailsafe.of(p) else PillFailsafe.None
        StatusModel.items(a, pre, heldTexts(critical, warnings, now), f, pill, failsafe)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), emptyList())

    /** The FC texts the pill holds: the critical text while its alert lasts, and the recent warnings. */
    private fun heldTexts(critical: ReceivedText?, warnings: List<ReceivedText>, now: Long): List<ReceivedText> =
        listOfNotNull(critical?.takeIf { now - it.atMs < AlertEngine.STATUS_TEXT_HOLD_MS }) +
            warnings.filter { now - it.atMs <= StatusModel.WARNING_TEXT_HOLD_MS }

    companion object {
        /** Pre-flight items show only on a vehicle known to be disarmed: unknown is not on the ground. */
        fun preflightShown(armed: Boolean?): Boolean = armed == false
    }
}
