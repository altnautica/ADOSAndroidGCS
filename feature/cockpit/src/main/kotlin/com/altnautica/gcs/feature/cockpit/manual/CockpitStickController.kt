package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.feature.cockpit.StickChip
import com.altnautica.gcs.feature.cockpit.alerts.RaisedAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The cockpit's controller stick control: the stream itself ([CockpitManualControl]), the
 * per-vehicle all-modes opt-in, and the alerts for stick control turning itself off and
 * for a low controller battery while the sticks fly.
 */
internal class CockpitStickController(
    private val scope: CoroutineScope,
    clock: MonotonicClock,
    private val vehicle: StateFlow<Vehicle?>,
    vehicleServices: StateFlow<VehicleServices?>,
    router: AdosInputRouter,
    private val settingsRepository: ControllerSettingsRepository,
    controllerManager: ControllerManager,
    loadout: StateFlow<Loadout>,
) {
    private val manual = CockpitManualControl(scope, vehicle, vehicleServices, router, settingsRepository.settings, loadout)

    val stick: StateFlow<StickChip> = manual.stick

    /** True while the sticks fly the vehicle. */
    val stickOn: StateFlow<Boolean> = stick.map { (it as? StickChip.Available)?.on == true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Stick control in every mode for the flown vehicle; null without a vehicle. */
    val allModes: StateFlow<Boolean?> = combine(vehicle, settingsRepository.settings) { v, s -> v?.let { it.id.value in s.allModesVehicles } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _raised = MutableStateFlow<RaisedAlert?>(null)

    /** The latest time stick control turned itself off, for the alert engine. */
    val raised: StateFlow<RaisedAlert?> = _raised.asStateFlow()

    /** The controller battery is low while the sticks fly. */
    val batteryLow: StateFlow<Boolean> = combine(stick, controllerManager.active) { s, c -> controllerBatteryLow(s, c?.batteryPct) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch { manual.off.collect { _raised.value = it.toAlert(clock.nowMs()) } }
    }

    fun toggle() = manual.toggle()

    fun forceOff() = manual.forceOff()

    fun setAllModes(on: Boolean) {
        val id = vehicle.value?.id ?: return
        scope.launch { settingsRepository.setAllModes(id, on) }
    }

    /** True when switching to [mode] now would turn stick control off. */
    fun endsStick(mode: String): Boolean = stickOn.value && !manual.allModes() && !manual.isAssisted(mode)
}
