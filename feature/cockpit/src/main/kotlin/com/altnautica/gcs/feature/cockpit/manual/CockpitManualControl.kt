package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.builtin.vehicleClass
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.ReservedInputs
import com.altnautica.gcs.feature.cockpit.StickChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why stick control turned itself off. It never turns itself back on. */
sealed interface StickOff {
    /** The vehicle left an assisted mode (or reports no mode) while the sticks flew. */
    data class ModeLeft(val mode: String) : StickOff

    /** The controller disconnected while the sticks flew; [armed] is the vehicle state at that moment. */
    data class ControllerLost(val armed: Boolean) : StickOff
}

/** Gamepad stick control: the stick chip and the MANUAL_CONTROL stream it switches. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitManualControl(
    scope: CoroutineScope,
    private val vehicle: StateFlow<Vehicle?>,
    vehicleServices: StateFlow<VehicleServices?>,
    router: AdosInputRouter,
    controllerSettings: Flow<ControllerSettings>,
    loadout: StateFlow<Loadout>,
) {
    private val stickOn = MutableStateFlow(false)

    private val _off = MutableSharedFlow<StickOff>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each time stick control turns off on its own, with the reason. */
    val off: SharedFlow<StickOff> = _off.asSharedFlow()

    private val settings: StateFlow<ControllerSettings> = controllerSettings.stateIn(scope, SharingStarted.Eagerly, ControllerSettings())

    private val stickAllowed: StateFlow<Boolean> = combine(vehicle.flatMapLatest { it?.reach ?: flowOf(null) }, vehicleServices) { reach, svc ->
        svc != null && (reach == Reach.DirectFc || reach == Reach.Lan || reach is Reach.ViaGround)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private data class ModeGate(val mode: String?, val allowed: Boolean)

    /** The current mode and whether the sticks may fly in it on this vehicle. */
    private val modeGate: StateFlow<ModeGate> = combine(
        vehicle.flatMapLatest { it?.telemetry?.heartbeat ?: flowOf(null) },
        vehicle,
        settings,
    ) { hb, v, s ->
        val mode = hb?.mode?.name
        val allModes = v != null && v.id.value in s.allModesVehicles
        ModeGate(mode, StickPolicy.allowed(mode, vehicleClass(hb?.vehicleType, mode), allModes))
    }.stateIn(scope, SharingStarted.Eagerly, ModeGate(null, false))

    val stick: StateFlow<StickChip> = combine(router.gamepadConnected, stickAllowed, modeGate, stickOn) { pad, link, gate, on ->
        when {
            !pad -> StickChip.NoGamepad
            !link -> StickChip.LinkBlocked
            !gate.allowed -> StickChip.ModeBlocked(gate.mode ?: UNKNOWN_MODE)
            else -> StickChip.Available(on)
        }
    }.stateIn(scope, SharingStarted.Eagerly, StickChip.NoGamepad)

    private val manualInput: StateFlow<ManualControlInput> = combine(router.axes, router.buttons, settings, loadout) { axes, pressed, s, l ->
        val e = s.expoPct / 100f
        // Mode 2: left stick throttle/yaw, right stick pitch/roll. Throttle takes no expo.
        ManualControlInput(
            pitch = applyExpo(axes.rightY, e),
            roll = applyExpo(axes.rightX, e),
            throttle = ((axes.leftY + 1f) / 2f).coerceIn(0f, 1f),
            yaw = applyExpo(axes.leftX, e),
            buttons = ReservedInputs.manualControlButtonMask(pressed, l),
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
            // Leaving an assisted mode turns stick control off; returning never resumes it.
            modeGate.collect { gate ->
                if (!gate.allowed && stickOn.value) {
                    forceOff()
                    _off.tryEmit(StickOff.ModeLeft(gate.mode ?: UNKNOWN_MODE))
                }
            }
        }
        scope.launch {
            // Losing the controller turns stick control off; a reconnect never resumes it.
            var wasConnected = router.gamepadConnected.value
            router.gamepadConnected.collect { connected ->
                if (wasConnected && !connected) {
                    val wasOn = stickOn.value
                    forceOff()
                    if (wasOn) _off.tryEmit(StickOff.ControllerLost(armed = vehicle.value?.telemetry?.heartbeat?.value?.armed == true))
                }
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

    /** True when [mode] is an assisted mode for the flown vehicle's airframe class. */
    fun isAssisted(mode: String): Boolean {
        val hb = vehicle.value?.telemetry?.heartbeat?.value
        return StickPolicy.isAssisted(mode, vehicleClass(hb?.vehicleType, mode))
    }

    /** True when the flown vehicle is opted in to stick control in every mode. */
    fun allModes(): Boolean = vehicle.value?.let { it.id.value in settings.value.allModesVehicles } == true

    private companion object {
        /** Shown in place of the mode name before the vehicle reports one. */
        const val UNKNOWN_MODE = "—"
    }
}

/** Stick expo: softens the centre while keeping full deflection; [e] is 0 (linear) to 1 (cubic). */
internal fun applyExpo(x: Float, e: Float): Float = (1f - e) * x + e * x * x * x
