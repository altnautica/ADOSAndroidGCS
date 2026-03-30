package com.altnautica.gcs.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ThemeOption(val label: String) {
    DARK("Dark"),
    OLED("OLED"),
}

enum class UnitSystem(val label: String) {
    METRIC("Metric"),
    IMPERIAL("Imperial"),
}

enum class MapProvider(val label: String) {
    MAPBOX("Mapbox"),
    OSM("OpenStreetMap"),
}

private object Keys {
    val THEME = stringPreferencesKey("theme")
    val UNITS = stringPreferencesKey("units")
    val MAP_PROVIDER = stringPreferencesKey("map_provider")
    val HUD_ENABLED = booleanPreferencesKey("hud_enabled")
    val COMPASS_ENABLED = booleanPreferencesKey("compass_enabled")
    val ALT_LADDER_ENABLED = booleanPreferencesKey("alt_ladder_enabled")
    val SPEED_LADDER_ENABLED = booleanPreferencesKey("speed_ladder_enabled")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ViewModel() {

    val theme: StateFlow<ThemeOption> = dataStore.data
        .map { prefs ->
            prefs[Keys.THEME]?.let { ThemeOption.valueOf(it) } ?: ThemeOption.DARK
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeOption.DARK)

    val units: StateFlow<UnitSystem> = dataStore.data
        .map { prefs ->
            prefs[Keys.UNITS]?.let { UnitSystem.valueOf(it) } ?: UnitSystem.METRIC
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UnitSystem.METRIC)

    val mapProvider: StateFlow<MapProvider> = dataStore.data
        .map { prefs ->
            prefs[Keys.MAP_PROVIDER]?.let { MapProvider.valueOf(it) } ?: MapProvider.MAPBOX
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MapProvider.MAPBOX)

    val hudEnabled: StateFlow<Boolean> = boolPref(Keys.HUD_ENABLED, true)
    val compassEnabled: StateFlow<Boolean> = boolPref(Keys.COMPASS_ENABLED, true)
    val altLadderEnabled: StateFlow<Boolean> = boolPref(Keys.ALT_LADDER_ENABLED, true)
    val speedLadderEnabled: StateFlow<Boolean> = boolPref(Keys.SPEED_LADDER_ENABLED, true)

    fun setTheme(option: ThemeOption) = setPref { it[Keys.THEME] = option.name }
    fun setUnits(option: UnitSystem) = setPref { it[Keys.UNITS] = option.name }
    fun setMapProvider(option: MapProvider) = setPref { it[Keys.MAP_PROVIDER] = option.name }
    fun setHudEnabled(enabled: Boolean) = setPref { it[Keys.HUD_ENABLED] = enabled }
    fun setCompassEnabled(enabled: Boolean) = setPref { it[Keys.COMPASS_ENABLED] = enabled }
    fun setAltLadderEnabled(enabled: Boolean) = setPref { it[Keys.ALT_LADDER_ENABLED] = enabled }
    fun setSpeedLadderEnabled(enabled: Boolean) = setPref { it[Keys.SPEED_LADDER_ENABLED] = enabled }

    private fun boolPref(key: Preferences.Key<Boolean>, default: Boolean): StateFlow<Boolean> {
        return dataStore.data
            .map { prefs -> prefs[key] ?: default }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), default)
    }

    private fun setPref(block: suspend (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            dataStore.edit { block(it) }
        }
    }
}
