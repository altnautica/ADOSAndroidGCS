package com.altnautica.gcs.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.link.direct.DirectEndpoint
import com.altnautica.gcs.core.link.direct.DirectLinkSettings
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.tips.TipsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loaded: Boolean = false,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val cockpit: CockpitSettings = CockpitSettings(),
    val demoEnabled: Boolean = false,
    val demoStaleSkill: Boolean = false,
    val endpoints: List<DirectEndpoint> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: AppSettingsRepository,
    private val cockpit: CockpitSettingsRepository,
    private val direct: DirectLinkSettings,
    private val demo: DemoSettings,
    private val controls: ControlsRepository,
    private val tips: TipsRepository,
    private val spec: CockpitSpecRepository,
    private val scenarios: DemoScenarioStore,
) : ViewModel() {

    /** The demo pilot's pinned flight state; null runs the demo live. */
    val demoScenario: StateFlow<DemoScenario?> = scenarios.pinned

    fun pinDemoScenario(scenario: DemoScenario?) = scenarios.pin(scenario)

    val state: StateFlow<SettingsUiState> = combine(
        app.appearance,
        cockpit.settings,
        combine(demo.enabled, demo.staleSkill) { on, stale -> on to stale },
        direct.endpoints,
    ) { a, c, (demoOn, stale), endpoints -> SettingsUiState(true, a, c, demoOn, stale, endpoints) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(theme: AdosThemeId) = launch { app.setTheme(theme) }

    fun setGloveMode(on: Boolean) = launch { app.setGloveMode(on) }

    fun setMaxBrightnessDefault(on: Boolean) = launch { cockpit.setMaxBrightnessDefault(on) }

    fun setAudioCallouts(on: Boolean) = launch { cockpit.setAudioCallouts(on) }

    fun setModeCallouts(on: Boolean) = launch { cockpit.setModeCallouts(on) }

    fun setVoicePrompts(on: Boolean) = launch { cockpit.setVoicePrompts(on) }

    fun setAltitudeReference(ref: AltitudeReference) = launch { cockpit.setAltitudeReference(ref) }

    fun setDensity(density: CockpitDensity) = launch { cockpit.setDensity(density) }

    fun setTelemetryPane(on: Boolean) = launch { cockpit.setTelemetryPane(on) }

    /** Moves the low band; the critical band is kept below it by the repository. */
    fun setBatteryLow(pct: Int) = launch {
        val bands = state.value.cockpit.batteryBands
        cockpit.setBatteryBands(pct, bands.criticalPct)
    }

    fun setBatteryCritical(pct: Int) = launch {
        val bands = state.value.cockpit.batteryBands
        cockpit.setBatteryBands(bands.lowPct, pct)
    }

    fun setDemoEnabled(on: Boolean) = launch { demo.setEnabled(on) }

    fun setDemoStaleSkill(on: Boolean) = launch { demo.setStaleSkill(on) }

    fun removeEndpoint(endpoint: DirectEndpoint) = launch { direct.removeEndpoint(endpoint) }

    /** Clears the controller tour's seen flag: the tour shows again on the next flight screen opened on the ground. */
    fun replayControllerTour() = launch { controls.setTourSeen(false) }

    /** Forgets every seen tip, so the flight screen tips start over. */
    fun resetTips() = launch { tips.reset() }

    /** The cockpit spec's shared string for [key]. */
    fun specText(key: String): String = spec.setupStrings.format(key)

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
