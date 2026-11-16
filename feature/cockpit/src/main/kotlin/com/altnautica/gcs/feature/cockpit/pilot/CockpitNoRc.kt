package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.cockpit.StopControls
import com.altnautica.gcs.core.skills.hud.NoRcText
import com.altnautica.gcs.core.skills.hud.SpecText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The cockpit's side of flying with no RC transmitter (`rc_receiver: absent`): whether the
 * posture is on, the on-screen sticks it turns on, the checklist's lock-screen warning, and
 * the link notification's Pause, Land and Return, which run [runBuiltin] while the cockpit lives.
 */
internal class CockpitNoRc(
    scope: CoroutineScope,
    stops: StopControls,
    specText: SpecText,
    pilot: StateFlow<PilotState?>,
    onScreenSticks: StateFlow<Boolean>,
    runBuiltin: (builtinId: String) -> Unit,
) {
    val text = NoRcText(specText)
    private val notification = stops.notification

    /** True while the pilot declares no RC transmitter. */
    val active: StateFlow<Boolean> = pilot.map { it?.noRc == true }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, pilot.value?.noRc == true)

    /** The on-screen sticks are on: the operator's setting, or no RC, where they are the only sticks. */
    val sticksEnabled: StateFlow<Boolean> = combine(onScreenSticks, active) { setting, noRc -> setting || noRc }
        .stateIn(scope, SharingStarted.Eagerly, onScreenSticks.value || active.value)

    init {
        val registration = stops.register(runBuiltin)
        scope.coroutineContext[Job]?.invokeOnCompletion { registration.close() }
    }

    /** The checklist's warning row while no RC is declared and the lock screen hides the link notification, else null. */
    fun lockScreenItem(p: PilotState?): ReadinessItem? {
        if (p?.noRc != true) return null
        val line = text.lockScreenHidden(p, notification.lockScreenHidden()) ?: return null
        return ReadinessItem(NoRcText.LOCKSCREEN_ITEM, ok = false, severity = ReadinessSeverity.Warn, msg = line)
    }
}
