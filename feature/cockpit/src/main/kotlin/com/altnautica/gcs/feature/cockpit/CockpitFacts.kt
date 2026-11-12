package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.hud.aglMark
import com.altnautica.gcs.core.skills.hud.freshPilot
import com.altnautica.gcs.core.skills.hud.pilotFresh as isPilotFresh
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import com.altnautica.gcs.feature.cockpit.chip.ChipContext
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChipResolver
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChipUi
import com.altnautica.gcs.feature.cockpit.chrome.FlightValues
import com.altnautica.gcs.feature.cockpit.flow.FlowUi
import com.altnautica.gcs.feature.cockpit.hud.layers.ArRules
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the flight screen derives from the pilot, the band and the feeds on the 2 Hz tick:
 * the flight-state chip, the bottom row's AGL, pilot freshness, the flow banners and the
 * controller's context. A frame that is not fresh decides none of them.
 */
internal class CockpitFacts(
    scope: CoroutineScope,
    private val clock: MonotonicClock,
    spec: CockpitSpecRepository,
    pilot: StateFlow<PilotState?>,
    tick: StateFlow<Long>,
    telemetry: StateFlow<VehicleTelemetry?>,
    autonomy: CockpitAutonomy,
    band: StateFlow<BandState>,
    minimap: StateFlow<MinimapState>,
    hudValues: StateFlow<FlightValues>,
    streaming: StateFlow<Boolean>,
    pickerOpen: StateFlow<Boolean>,
    alerts: StateFlow<List<CockpitAlert>>,
    settings: StateFlow<CockpitSettings>,
) {
    private val chipResolver = FlightStateChipResolver(spec.flightState, spec.tokens, spec.setupStrings.strings)
    private val missionCompleteSince = MutableStateFlow<Long?>(null)

    init {
        scope.launch {
            pilot.map { it?.mission?.state }.distinctUntilChanged().collect { s ->
                missionCompleteSince.value = if (s == MissionState.Complete) clock.nowMs() else null
            }
        }
    }

    /** The flight-state chip, re-evaluated on every pilot frame and on the 2 Hz tick for staleness. */
    val chip: StateFlow<FlightStateChipUi> = combine(pilot, tick, band, missionCompleteSince, minimap) { p, _, b, done, map ->
        chipResolver.resolve(
            p,
            ChipContext(
                nowMs = clock.nowMs(),
                missionCompleteSinceMs = done,
                homeDistM = homeDistanceM(map),
                goalDistM = p?.goalNed?.let { goalDistanceM(it, telemetry.value?.localPosition?.value) },
                missionEtaS = autonomy.layers.store.ar.let { ArRules.nextWaypointEtaS(it.cur, it.ageMs(autonomy.nowMs())) },
                vtolTransitioning = telemetry.value?.vtolTransitioning?.value == true,
                fcModeName = b.modeName,
                modeDetail = p?.let { FlightStateChipResolver.modeDetail(it.ownerLabel, it.skill?.label?.uppercase()) },
            ),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), chipResolver.resolve(null, ChipContext(clock.nowMs())))

    /**
     * The bottom row. AGL comes from the follow feed while it is fresh, else from the agent's
     * pilot while it is fresh; a height not measured by a rangefinder carries its reference
     * as a tag. The 2 Hz tick re-checks freshness, so AGL goes to a dash with the other
     * cells after a link loss.
     */
    val flightValues: StateFlow<FlightValues> = combine(hudValues, autonomy.feed.follow, pilot, tick) { v, f, p, _ ->
        val strings = spec.setupStrings
        val follow = f?.takeIf { autonomy.nowMs() - it.atMs <= TargetState.FOLLOW_STALE_MS }?.value?.takeIf { it.aglM != null }
        val fromPilot = p?.takeIf { isPilotFresh(it.receivedAtMs, clock.nowMs()) && it.aglM != null }
        val (agl, tag) = when {
            v == FlightValues.Empty -> null to null
            // The pilot's height is measured only from a rangefinder, else it is the EKF height
            // above home; the follow kind's is measured unless it is the barometer's.
            fromPilot != null -> fromPilot.aglM to strings.aglMark(home = fromPilot.aglSource != "rangefinder")
            follow != null -> follow.aglM to strings.aglMark(baro = follow.aglSource == "baro")
            else -> null to null
        }
        if (agl == v.aglM && tag == v.aglTag) v else v.copy(aglM = agl, aglTag = tag)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), FlightValues.Empty)

    /**
     * True while the newest pilot frame is younger than the spec's `stale_after_ms`; re-checked
     * on the 2 Hz tick, so everything derived from a stalled pilot hides instead of freezing.
     */
    val pilotFresh: StateFlow<Boolean> = combine(pilot, tick) { p, _ ->
        isPilotFresh(p?.receivedAtMs, clock.nowMs())
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)

    /**
     * Banners, gate ring, stick session, countdowns and the launch steps from the pilot state.
     * A frame that is not fresh ([isPilotFresh]) shows none of them: a frozen countdown or
     * handover would read as live.
     */
    val flow: StateFlow<FlowUi> = combine(pilot, streaming, tick) { p, on, _ ->
        if (p != null && !isPilotFresh(p.receivedAtMs, clock.nowMs())) FlowUi() else FlowUi.of(p, PilotWire.SRC_ANDROID, on)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), FlowUi())

    /** The controller's context; only a fresh pilot frame decides armed and landed, an old one falls back to the band. */
    val controllerContext: StateFlow<ControllerContext> = combine(pilot, band, pickerOpen, alerts, tick) { p, b, open, a, _ ->
        ControllerContext.of(
            pilot = freshPilot(p, clock.nowMs()),
            armed = b.armed,
            modeName = b.modeName,
            pickerOpen = open,
            lowBattery = (b.batteryPct ?: 100) <= settings.value.batteryBands.criticalPct,
            failsafe = a.any { it.level == AlertLevel.Critical },
        )
    }.stateIn(scope, SharingStarted.Eagerly, ControllerContext())
}
