package com.altnautica.gcs.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.link.direct.DirectEndpoint
import com.altnautica.gcs.core.link.direct.DirectLinkSettings
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
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
    val endpoints: List<DirectEndpoint> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: AppSettingsRepository,
    private val cockpit: CockpitSettingsRepository,
    private val direct: DirectLinkSettings,
    private val demo: DemoSettings,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        app.appearance,
        cockpit.settings,
        demo.enabled,
        direct.endpoints,
    ) { a, c, demoOn, endpoints -> SettingsUiState(true, a, c, demoOn, endpoints) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(theme: AdosThemeId) = launch { app.setTheme(theme) }

    fun setGloveMode(on: Boolean) = launch { app.setGloveMode(on) }

    fun setMaxBrightnessDefault(on: Boolean) = launch { cockpit.setMaxBrightnessDefault(on) }

    fun setAudioCallouts(on: Boolean) = launch { cockpit.setAudioCallouts(on) }

    fun setModeCallouts(on: Boolean) = launch { cockpit.setModeCallouts(on) }

    /** Moves the low band; the critical band is kept below it by the repository. */
    fun setBatteryLow(pct: Int) = launch {
        val bands = state.value.cockpit.batteryBands
        cockpit.setBatteryBands(pct, minOf(bands.criticalPct, pct - 1))
    }

    fun setBatteryCritical(pct: Int) = launch {
        val bands = state.value.cockpit.batteryBands
        cockpit.setBatteryBands(bands.lowPct, pct)
    }

    fun setDemoEnabled(on: Boolean) = launch { demo.setEnabled(on) }

    fun removeEndpoint(endpoint: DirectEndpoint) = launch { direct.removeEndpoint(endpoint) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
