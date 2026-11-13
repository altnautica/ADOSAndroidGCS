package com.altnautica.gcs.feature.cockpit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.feature.cockpit.tips.TipFacts
import com.altnautica.gcs.feature.cockpit.tips.TipLayer
import com.altnautica.gcs.feature.cockpit.tips.TipsHandle
import com.altnautica.gcs.feature.cockpit.tips.tipsRepository
import com.altnautica.gcs.feature.cockpit.flow.FlowUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** The flight facts the tip queue gates on; a null arm or airborne fact is unknown. */
internal data class FlightTipFacts(val armed: Boolean?, val airborne: Boolean?, val gotoOffered: Boolean)

/**
 * Armed, airborne and the fly-here offer for the tips. The pilot is trusted only while its
 * frames are fresh ([pilotFresh]); otherwise the heartbeat's [bandArmed] decides: a known arm
 * dismisses the tip for good, and an unknown arm state (null) counts as possibly airborne, so
 * no tip shows and none is marked seen.
 */
internal fun flightTipFacts(pilot: PilotState?, pilotFresh: Boolean, bandArmed: Boolean?): FlightTipFacts {
    val live = pilot?.takeIf { pilotFresh }
    return FlightTipFacts(
        armed = if (live != null) live.armed else bandArmed,
        airborne = if (live != null) (if (live.armStateUnknown) null else live.knownAirborne) else bandArmed,
        gotoOffered = live != null && PilotAction.GOTO in live.surfaceOps,
    )
}

/** The view model's part of the tip facts: the flight facts and whether an alert banner shows. */
internal data class TipFlightFacts(val flight: FlightTipFacts, val alertBannerVisible: Boolean)

/**
 * [TipFlightFacts] from the fresh pilot ([freshPilot], null while stale), the band's arm state,
 * the alerts and the flow state. It changes only when a fact does, so the tip host does not
 * recompose on every pilot frame.
 */
internal fun tipFlightFacts(
    scope: CoroutineScope,
    freshPilot: StateFlow<PilotState?>,
    band: StateFlow<BandState>,
    alerts: StateFlow<List<CockpitAlert>>,
    flow: StateFlow<FlowUi>,
): StateFlow<TipFlightFacts> {
    fun of(p: PilotState?, b: BandState, a: List<CockpitAlert>, f: FlowUi) = TipFlightFacts(
        flightTipFacts(p, pilotFresh = p != null, bandArmed = b.armed),
        alertBannerVisible = a.any { it.level == AlertLevel.Critical } ||
            f.launch != null || f.banner != null || f.sticksWith != null || f.linkLossInS != null || f.rthCountdownS != null,
    )
    return combine(freshPilot, band, alerts, flow, ::of).distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), of(freshPilot.value, band.value, alerts.value, flow.value))
}

/** What the tip queue needs from the flight screen ([TipFacts]). */
@Composable
internal fun tipFacts(vm: CockpitViewModel, status: StatusListState): TipFacts {
    val flight by vm.tipFlight.collectAsStateWithLifecycle()
    val tourStep by vm.tourStep.collectAsStateWithLifecycle()
    val gimbal by vm.gimbal.available.collectAsStateWithLifecycle()
    val skills by vm.skillsAvailable.collectAsStateWithLifecycle()
    val sheets = openSheets(vm)
    return TipFacts(
        armed = flight.flight.armed,
        airborne = flight.flight.airborne,
        sheetOrMenuOpen = sheets.isNotEmpty() || status.menuOpen,
        tourVisible = tourStep != null,
        skillsAvailable = skills,
        gotoOffered = flight.flight.gotoOffered,
        gimbalAvailable = gimbal,
        alertBannerVisible = flight.alertBannerVisible,
    )
}

/**
 * The tip layer with its facts. Its own restartable scope: the facts change only when a flight
 * fact, a sheet or the tour does ([tipFlightFacts]), which recomposes only this host.
 */
@Composable
internal fun TipHost(vm: CockpitViewModel, status: StatusListState, engine: TipsEngine, handle: TipsHandle, modifier: Modifier) {
    TipLayer(tipFacts(vm, status), tipsRepository(LocalContext.current), engine, modifier, handle = handle)
}
