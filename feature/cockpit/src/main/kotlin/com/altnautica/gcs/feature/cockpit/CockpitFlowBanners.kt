package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.linkLossLine
import com.altnautica.gcs.feature.cockpit.flow.HandoverBanner
import com.altnautica.gcs.feature.cockpit.flow.LaunchProgressBanner
import com.altnautica.gcs.feature.cockpit.flow.LinkLossBanner
import com.altnautica.gcs.feature.cockpit.flow.LowBatteryBanner
import com.altnautica.gcs.feature.cockpit.flow.MissionStallBanner
import com.altnautica.gcs.feature.cockpit.flow.SticksWithBanner
import com.altnautica.gcs.feature.cockpit.flow.RouteCheckBanner
import com.altnautica.gcs.core.skills.hud.ConfinedText
import androidx.compose.runtime.derivedStateOf

/** The link-loss action the pilot reports ("rtl", "land", …), from its own field or its banner. */
internal fun linkLossAction(pilot: PilotState?): String? = pilot?.linkLossAction ?: pilot?.banner?.args?.get("action")

/**
 * Handover banner, another surface holding the sticks, the mission stall, link-loss and
 * low-battery countdowns, and the confined profile's route check (beside the stall overlay
 * it gives way: the overlay and the pilot's own banner already say why the vehicle holds).
 * Of the pilot's frames it reads only the link-loss action and the route check, so a new
 * frame that changes neither does not recompose the banners.
 */
@Composable
internal fun FlowBanners(vm: CockpitViewModel, modifier: Modifier) {
    val flow by vm.flow.collectAsStateWithLifecycle()
    val pilot = vm.pilotState.collectAsStateWithLifecycle()
    val action by remember { derivedStateOf { linkLossAction(pilot.value) } }
    val confined = remember(vm) { ConfinedText(SpecText(vm.cockpitSpec.setupStrings)) }
    val routeCheck by remember(confined) { derivedStateOf { confined.report(pilot.value?.routeCheck) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        flow.launch?.let { LaunchProgressBanner(it, onAbort = vm::abortLaunch) }
        flow.banner?.let { HandoverBanner(it) }
        flow.sticksWith?.let { SticksWithBanner(it, vm::takeSticks) }
        flow.linkLossInS?.let { s ->
            // The configured action in words ("Return home", "Land", "Hold"); a dash when unknown.
            SpecText(vm.cockpitSpec.setupStrings).linkLossLine(s, action)?.let { LinkLossBanner(it) }
        }
        flow.stall?.let { MissionStallBanner(it, vm::onStallAction) }
        if (flow.stall == null) routeCheck?.let { RouteCheckBanner(it) }
        flow.rthCountdownS?.let { LowBatteryBanner(it, flow.lowBatteryAction, vm::cancelRthCountdown) }
    }
}
