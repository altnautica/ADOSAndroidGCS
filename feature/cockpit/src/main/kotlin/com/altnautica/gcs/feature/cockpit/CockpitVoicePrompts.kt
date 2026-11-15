package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.MissionStallKind
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The pilot banner announcing a skipped waypoint. */
private const val WP_SKIPPED_BANNER = "pilot.banner.mission_wp_skipped"

/**
 * Speaks the pilot's changes while voice prompts are on: who flies (ADOS or the FC), the
 * mission's state, the start of a low-battery return countdown, a stalled mission or return,
 * and a skipped waypoint.
 */
internal fun CoroutineScope.launchVoicePrompts(
    pilot: StateFlow<PilotState?>,
    settings: StateFlow<CockpitSettings>,
    strings: () -> SetupStringsSpec,
    announcer: AlertAnnouncer,
) = launch {
    var lastOwner: PilotOwner? = null
    var lastMission: MissionState? = null
    var lastRth = false
    var lastStall: MissionStallKind? = null
    var lastSkipped: PilotBanner? = null
    pilot.collect { p ->
        if (p == null || !settings.value.voicePrompts) return@collect
        val s = strings()
        val owner = p.owner
        if (lastOwner != null && owner != lastOwner) {
            when (owner) {
                PilotOwner.Pilot -> announcer.speak(s.format("voice.ados_control"))
                PilotOwner.FcMode -> announcer.speak(s.format("voice.fc_control"))
                else -> Unit
            }
        }
        lastOwner = owner
        val mission = p.mission?.state
        if (lastMission != null && mission != lastMission) {
            when (mission) {
                MissionState.Active -> announcer.speak(s.format("voice.mission_active"))
                MissionState.Paused -> announcer.speak(s.format("voice.mission_paused"))
                MissionState.Complete -> announcer.speak(s.format("voice.mission_complete"))
                MissionState.Returning -> announcer.speak(s.format("voice.rth"))
                else -> Unit
            }
        }
        lastMission = mission
        val rth = p.rthCountdownS != null
        if (rth && !lastRth) announcer.speak(s.format("voice.low_battery"))
        lastRth = rth
        val stall = p.missionStall?.kind
        if (stall != null && stall != lastStall) {
            when (stall) {
                MissionStallKind.Mission -> announcer.speak(s.format("voice.mission_stalled"))
                MissionStallKind.Return -> announcer.speak(s.format("voice.return_blocked"))
            }
        }
        lastStall = stall
        val skipped = p.banner?.takeIf { it.textKey == WP_SKIPPED_BANNER }
        if (skipped != null && skipped != lastSkipped) announcer.speak(s.format("voice.waypoint_skipped"))
        lastSkipped = skipped
    }
}
