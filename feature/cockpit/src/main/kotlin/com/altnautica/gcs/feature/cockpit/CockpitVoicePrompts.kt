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

/** The skip banners and the voice line each speaks once it newly arrives (the shared `SKIP_VOICE`). */
private val SKIP_VOICE = mapOf(
    "pilot.banner.mission_wp_skipped" to "voice.waypoint_skipped",
    "pilot.banner.mission_wp_inside_obstacle" to "voice.waypoint_unreachable",
    "pilot.banner.mission_wp_no_path" to "voice.waypoint_unreachable",
)

/**
 * Speaks the pilot's changes while voice prompts are on: who flies (ADOS or the FC), the
 * mission's state, the start of a low-battery return countdown, a stalled mission or return,
 * a detour round a blocked leg (again when it moves to another item), a return routed round
 * what blocked it, and a skipped or unreachable waypoint.
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
    var lastDetourSeq: Int? = null
    var lastRouted = false
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
        val detourSeq = p.missionDetour?.seq
        if (detourSeq != null && detourSeq != lastDetourSeq) announcer.speak(s.format("voice.mission_detour"))
        lastDetourSeq = detourSeq
        val routed = p.returnRoute != null
        if (routed && !lastRouted) announcer.speak(s.format("voice.return_routed"))
        lastRouted = routed
        val skipped = p.banner?.takeIf { it.textKey in SKIP_VOICE }
        if (skipped != null && skipped != lastSkipped) announcer.speak(s.format(SKIP_VOICE.getValue(skipped.textKey)))
        lastSkipped = skipped
    }
}
