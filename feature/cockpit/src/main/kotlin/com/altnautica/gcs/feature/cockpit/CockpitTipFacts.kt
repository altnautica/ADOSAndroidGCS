package com.altnautica.gcs.feature.cockpit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.feature.cockpit.tips.TipFacts

/** The flight facts the tip queue gates on. */
internal data class FlightTipFacts(val armed: Boolean, val airborne: Boolean, val gotoOffered: Boolean)

/**
 * Armed, airborne and the fly-here offer for the tips. The pilot is trusted only while its
 * frames are fresh ([pilotFresh]); otherwise the heartbeat's [bandArmed] decides: a known arm
 * dismisses the tip for good, and an unknown arm state counts as possibly airborne, so no tip
 * shows and none is marked seen.
 */
internal fun flightTipFacts(pilot: PilotState?, pilotFresh: Boolean, bandArmed: Boolean?): FlightTipFacts {
    val live = pilot?.takeIf { pilotFresh }
    return FlightTipFacts(
        armed = live?.armed ?: (bandArmed == true),
        airborne = live?.let { it.armed && !it.landed } ?: (bandArmed != false),
        gotoOffered = live != null && PilotAction.GOTO in live.surfaceOps,
    )
}

/** What the tip queue needs from the flight screen ([TipFacts]). */
@Composable
internal fun tipFacts(vm: CockpitViewModel, status: StatusListState): TipFacts {
    val pilot by vm.pilotState.collectAsStateWithLifecycle()
    val fresh by vm.pilotFresh.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val tourStep by vm.tourStep.collectAsStateWithLifecycle()
    val gimbal by vm.gimbal.available.collectAsStateWithLifecycle()
    val skills by vm.skillsAvailable.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val flow by vm.flow.collectAsStateWithLifecycle()
    val sheets = openSheets(vm)
    val flight = flightTipFacts(pilot, fresh, band.armed)
    return TipFacts(
        armed = flight.armed,
        airborne = flight.airborne,
        sheetOrMenuOpen = sheets.isNotEmpty() || status.menuOpen,
        tourVisible = tourStep != null,
        skillsAvailable = skills,
        gotoOffered = flight.gotoOffered,
        gimbalAvailable = gimbal,
        alertBannerVisible = alerts.any { it.level == AlertLevel.Critical } ||
            flow.launch != null || flow.banner != null || flow.sticksWith != null || flow.linkLossInS != null || flow.rthCountdownS != null,
    )
}
