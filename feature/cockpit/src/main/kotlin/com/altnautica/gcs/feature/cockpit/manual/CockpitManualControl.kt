package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.ReservedInputs
import com.altnautica.gcs.feature.cockpit.StickChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Gamepad stick control: the stick chip and the MANUAL_CONTROL stream it switches. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitManualControl(
    scope: CoroutineScope,
    vehicle: StateFlow<Vehicle?>,
    vehicleServices: StateFlow<VehicleServices?>,
    router: AdosInputRouter,
) {
    private val stickOn = MutableStateFlow(false)

    private val stickAllowed: StateFlow<Boolean> = combine(vehicle.flatMapLatest { it?.reach ?: flowOf(null) }, vehicleServices) { reach, svc ->
        svc != null && (reach == Reach.DirectFc || reach == Reach.Lan || reach is Reach.ViaGround)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val stick: StateFlow<StickChip> = combine(router.gamepadConnected, stickAllowed, stickOn) { pad, allowed, on ->
        when {
            !pad -> StickChip.NoGamepad
            !allowed -> StickChip.LinkBlocked
            else -> StickChip.Available(on)
        }
    }.stateIn(scope, SharingStarted.Eagerly, StickChip.NoGamepad)

    private val manualInput: StateFlow<ManualControlInput> = combine(router.axes, router.buttons) { axes, pressed ->
        // Mode 2: left stick throttle/yaw, right stick pitch/roll.
        ManualControlInput(
            pitch = axes.rightY,
            roll = axes.rightX,
            throttle = ((axes.leftY + 1f) / 2f).coerceIn(0f, 1f),
            yaw = axes.leftX,
            buttons = ReservedInputs.manualControlButtonMask(pressed),
        )
    }.stateIn(scope, SharingStarted.Eagerly, ManualControlInput.NEUTRAL)

    private var streamerOwner: VehicleServices? = null

    init {
        scope.launch {
            combine(stick, vehicleServices) { chip, svc -> Pair((chip as? StickChip.Available)?.on == true, svc) }
                .collect { (on, svc) ->
                    stopStreamer()
                    if (on && svc != null) {
                        svc.manualControl.start(manualInput)
                        streamerOwner = svc
                    }
                }
        }
        scope.launch {
            // Losing the controller turns stick control off; a reconnect never resumes it.
            var wasConnected = router.gamepadConnected.value
            router.gamepadConnected.collect { connected ->
                if (wasConnected && !connected) forceOff()
                wasConnected = connected
            }
        }
    }

    private fun stopStreamer() {
        streamerOwner?.manualControl?.stop()
        streamerOwner = null
    }

    fun toggle() {
        if (stick.value is StickChip.Available) stickOn.update { !it }
    }

    /** Turns stick control off and stops the stream at once. */
    fun forceOff() {
        stickOn.value = false
        stopStreamer()
    }
}
