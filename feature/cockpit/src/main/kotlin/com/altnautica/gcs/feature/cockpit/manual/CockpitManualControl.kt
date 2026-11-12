package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.builtin.vehicleClass
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.feature.cockpit.pilot.StickConditioning
import com.altnautica.gcs.feature.cockpit.pilot.StickRoute
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

/**
 * Gamepad stick control on the flight controller's MANUAL_CONTROL path: the stick chip and
 * the stream it switches. It streams only on [StickRoute.FcManual] (`fc_manual`, or a flight
 * controller without an onboard pilot); with the onboard pilot the sticks go to the pilot
 * channel instead and the chip reads [StickChip.Pilot].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitManualControl(
    scope: CoroutineScope,
    private val vehicle: StateFlow<Vehicle?>,
    vehicleServices: StateFlow<VehicleServices?>,
    router: AdosInputRouter,
    controllerSettings: Flow<ControllerSettings>,
    /** Buttons bound to cockpit actions; they never reach the flight controller. */
    boundButtons: StateFlow<Int>,
    route: StateFlow<StickRoute>,
    pilotStreaming: StateFlow<Boolean>,
    /** The pad as the router reports it, raw axes included. */
    pad: StateFlow<PadSnapshot>,
    /** Calibration, dead zone and stick mode, shared with the pilot stream. */
    conditioning: StateFlow<StickConditioning>,
) {
    private val stickOn = MutableStateFlow(false)

    private val _off = MutableSharedFlow<StickOff>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each time stick control turns off on its own, with the reason. */
    val off: SharedFlow<StickOff> = _off.asSharedFlow()

    private val settings: StateFlow<ControllerSettings> = controllerSettings.stateIn(scope, SharingStarted.Eagerly, ControllerSettings())

    private val stickAllowed: StateFlow<Boolean> = combine(vehicle.flatMapLatest { it?.reach ?: flowOf(null) }, vehicleServices, route) { reach, svc, r ->
        r == StickRoute.FcManual && svc != null && (reach == Reach.DirectFc || reach == Reach.Lan || reach is Reach.ViaGround)
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

    private val manualStick: Flow<StickChip> = combine(router.gamepadConnected, stickAllowed, modeGate, stickOn) { pad, link, gate, on ->
        when {
            !pad -> StickChip.NoGamepad
            !link -> StickChip.LinkBlocked
            !gate.allowed -> StickChip.ModeBlocked(gate.mode ?: UNKNOWN_MODE)
            else -> StickChip.Available(on)
        }
    }

    val stick: StateFlow<StickChip> = combine(manualStick, route, router.gamepadConnected, pilotStreaming) { manual, r, pad, streaming ->
        if (r == StickRoute.Pilot && pad) StickChip.Pilot(streaming) else manual
    }.stateIn(scope, SharingStarted.Eagerly, StickChip.NoGamepad)

    /**
     * The MANUAL_CONTROL input from the same conditioning the pilot stream uses: the pad's
     * calibration, the radial dead zone and the operator's stick mode (Mode 1 or 2). Expo
     * softens pitch, roll and yaw; throttle takes none and maps -1..1 onto 0..1.
     */
    private val manualInput: StateFlow<ManualControlInput> = combine(pad, conditioning, router.buttons, settings, boundButtons) { p, c, pressed, s, bound ->
        val e = s.expoPct / 100f
        val axes = c.condition(p)
        ManualControlInput(
            pitch = applyExpo(axes.pitch, e),
            roll = applyExpo(axes.roll, e),
            throttle = ((axes.throttle + 1f) / 2f).coerceIn(0f, 1f),
            yaw = applyExpo(axes.yaw, e),
            buttons = Ds4Bindings.manualControlButtonMask(pressed, bound),
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
