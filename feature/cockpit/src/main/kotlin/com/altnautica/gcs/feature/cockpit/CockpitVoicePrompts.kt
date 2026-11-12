package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Speaks the pilot's changes while voice prompts are on: who flies (ADOS or the FC), the
 * mission's state, and the start of a low-battery return countdown.
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
    }
}
