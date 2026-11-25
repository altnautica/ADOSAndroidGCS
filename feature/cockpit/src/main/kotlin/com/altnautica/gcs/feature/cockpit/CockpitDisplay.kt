package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The cockpit views Triangle (or `V`) cycles through. */
enum class CockpitView(val id: String) { Pilot("pilot"), Map("map"), Split("split"), Vision("vision") }

/** The cockpit's display settings: the stored settings, screen brightness, the split view's share and the on-screen sticks switch. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitDisplaySettings(
    private val scope: CoroutineScope,
    private val settingsRepo: CockpitSettingsRepository,
    controls: ControlsRepository,
    private val vehicleNodeId: StateFlow<DeviceId?>,
) {
    val settings: StateFlow<CockpitSettings> =
        settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, CockpitSettings())

    private val _maxBrightness = MutableStateFlow<Boolean?>(null)

    /** Screen at full brightness; starts from the settings default. */
    val maxBrightness: StateFlow<Boolean> = combine(_maxBrightness, settings) { override, s -> override ?: s.maxBrightnessDefault }
        .stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)

    fun setMaxBrightness(on: Boolean) { _maxBrightness.value = on }

    fun setAltitudeReference(ref: AltitudeReference) = scope.launch { settingsRepo.setAltitudeReference(ref) }

    fun setDensity(density: CockpitDensity) = scope.launch { settingsRepo.setDensity(density) }

    /** The split view's video share for the flown vehicle, stored per vehicle. */
    val splitRatio: StateFlow<Float> = vehicleNodeId.flatMapLatest { id ->
        id?.let { settingsRepo.splitRatio(it.value) } ?: flowOf(CockpitSettingsRepository.DEFAULT_SPLIT)
    }.stateIn(scope, SharingStarted.Eagerly, CockpitSettingsRepository.DEFAULT_SPLIT)

    fun setSplitRatio(ratio: Float) {
        val id = vehicleNodeId.value ?: return
        scope.launch { settingsRepo.setSplitRatio(id.value, ratio) }
    }

    fun setTelemetryPane(on: Boolean) = scope.launch { settingsRepo.setTelemetryPane(on) }

    /** On-screen sticks enabled in Settings › Controls (default off). */
    val onScreenSticksEnabled: StateFlow<Boolean> = controls.onScreenSticks.stateIn(scope, SharingStarted.Eagerly, false)
}

/** The cockpit view on screen; each vehicle reopens on the view it was last flown with. [onShown] gets the shown view's id. */
internal class CockpitViews(
    private val scope: CoroutineScope,
    private val vehicleNodeId: StateFlow<DeviceId?>,
    private val settingsRepo: CockpitSettingsRepository,
    private val onShown: (String) -> Unit,
) {
    private val _view = MutableStateFlow(CockpitView.Pilot)
    val view: StateFlow<CockpitView> = _view.asStateFlow()

    /** Shows [v] and stores it for the flown vehicle, so its cockpit reopens on the same view. */
    fun set(v: CockpitView) {
        show(v)
        val id = vehicleNodeId.value ?: return
        scope.launch { settingsRepo.setView(id.value, v.id) }
    }

    private fun show(v: CockpitView) {
        _view.value = v
        onShown(v.id)
    }

    init {
        scope.launch {
            vehicleNodeId.filterNotNull().distinctUntilChanged().collect { id ->
                val stored = settingsRepo.view(id.value).first()
                CockpitView.entries.firstOrNull { it.id == stored }?.let(::show)
            }
        }
    }

    fun next() = set(CockpitView.entries[(_view.value.ordinal + 1) % CockpitView.entries.size])
}
