package com.altnautica.gcs.core.data.cockpit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Altitude tape reference: home-relative or mean sea level. */
enum class AltitudeReference { Rel, Msl }

/** Cockpit density. */
enum class CockpitDensity { Min, Std, Full }

/**
 * Operator battery bands: at or below [lowPct] warns, at or below [criticalPct] is critical.
 * [DEFAULT] stands for "not picked yet": the cockpit then takes the low band from its spec's
 * `layout.json` `battery.low_fallback_pct`, raised to the battery engine's reserve.
 */
data class BatteryBands(val lowPct: Int, val criticalPct: Int) {
    companion object {
        val DEFAULT = BatteryBands(lowPct = 20, criticalPct = 10)
    }
}

data class CockpitSettings(
    val batteryBands: BatteryBands = BatteryBands.DEFAULT,
    /** Spoken critical alerts. */
    val audioCallouts: Boolean = true,
    /** Spoken flight-mode changes. */
    val modeCallouts: Boolean = true,
    /** Cockpit opens with the screen at full brightness. */
    val maxBrightnessDefault: Boolean = false,
    val altitudeReference: AltitudeReference = AltitudeReference.Rel,
    val density: CockpitDensity = CockpitDensity.Std,
    /** Telemetry side pane on expanded widths. */
    val telemetryPane: Boolean = true,
    /** Spoken flight-flow prompts: handover, return home, low battery and mission state changes. */
    val voicePrompts: Boolean = true,
)

/** Cockpit preferences in the app settings store; the settings screen edits the same values. */
@Singleton
class CockpitSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<CockpitSettings> = dataStore.data.map { p ->
        val d = CockpitSettings()
        CockpitSettings(
            batteryBands = BatteryBands(
                lowPct = p[BATT_LOW] ?: d.batteryBands.lowPct,
                criticalPct = p[BATT_CRIT] ?: d.batteryBands.criticalPct,
            ),
            audioCallouts = p[AUDIO] ?: d.audioCallouts,
            modeCallouts = p[MODE_CALLOUTS] ?: d.modeCallouts,
            maxBrightnessDefault = p[MAX_BRIGHTNESS] ?: d.maxBrightnessDefault,
            altitudeReference = p[ALT_REF]?.let { runCatching { AltitudeReference.valueOf(it) }.getOrNull() } ?: d.altitudeReference,
            density = p[DENSITY]?.let { runCatching { CockpitDensity.valueOf(it) }.getOrNull() } ?: d.density,
            telemetryPane = p[TELEMETRY_PANE] ?: d.telemetryPane,
            voicePrompts = p[VOICE_PROMPTS] ?: d.voicePrompts,
        )
    }.distinctUntilChanged()

    /** Sets both bands: low in [BATTERY_LOW_MIN]..[BATTERY_LOW_MAX], critical in [criticalRange] of the low band. */
    suspend fun setBatteryBands(lowPct: Int, criticalPct: Int) = dataStore.edit {
        val low = lowPct.coerceIn(BATTERY_LOW_MIN, BATTERY_LOW_MAX)
        it[BATT_LOW] = low
        val critical = criticalRange(low)
        it[BATT_CRIT] = criticalPct.coerceIn(critical.first, critical.last)
    }

    suspend fun setAudioCallouts(on: Boolean) = dataStore.edit { it[AUDIO] = on }

    suspend fun setModeCallouts(on: Boolean) = dataStore.edit { it[MODE_CALLOUTS] = on }

    suspend fun setMaxBrightnessDefault(on: Boolean) = dataStore.edit { it[MAX_BRIGHTNESS] = on }

    suspend fun setAltitudeReference(ref: AltitudeReference) = dataStore.edit { it[ALT_REF] = ref.name }

    suspend fun setDensity(density: CockpitDensity) = dataStore.edit { it[DENSITY] = density.name }

    /** Video share of a medium/expanded split for one vehicle, 0.3..0.7 (views.json `split.divider`); half when unset. */
    fun splitRatio(vehicleId: String): Flow<Float> =
        dataStore.data.map { (it[splitKey(vehicleId)] ?: DEFAULT_SPLIT).coerceIn(MIN_SPLIT, MAX_SPLIT) }.distinctUntilChanged()

    suspend fun setSplitRatio(vehicleId: String, ratio: Float) =
        dataStore.edit { it[splitKey(vehicleId)] = ratio.coerceIn(MIN_SPLIT, MAX_SPLIT) }

    /** The cockpit main view id last picked for one vehicle; null when unset. */
    fun view(vehicleId: String): Flow<String?> = dataStore.data.map { it[viewKey(vehicleId)] }.distinctUntilChanged()

    suspend fun setView(vehicleId: String, viewId: String) = dataStore.edit { it[viewKey(vehicleId)] = viewId }

    suspend fun setTelemetryPane(on: Boolean) = dataStore.edit { it[TELEMETRY_PANE] = on }

    suspend fun setVoicePrompts(on: Boolean) = dataStore.edit { it[VOICE_PROMPTS] = on }

    /** The minimap frame pin for one vehicle: `auto`, `local` or `map`; `auto` when unset. */
    fun minimapMode(vehicleId: String): Flow<String> = dataStore.data.map { it[minimapKey(vehicleId)] ?: MINIMAP_AUTO }

    suspend fun setMinimapMode(vehicleId: String, mode: String) = dataStore.edit { it[minimapKey(vehicleId)] = mode }

    /** The video data-layer state for one vehicle (level, flags, depth options) as the cockpit's JSON; null when unset. */
    fun layers(vehicleId: String): Flow<String?> = dataStore.data.map { it[layersKey(vehicleId)] }

    suspend fun setLayers(vehicleId: String, json: String) = dataStore.edit { it[layersKey(vehicleId)] = json }

    companion object {
        const val MIN_SPLIT = 0.3f
        const val MAX_SPLIT = 0.7f
        const val DEFAULT_SPLIT = 0.5f
        const val BATTERY_LOW_MIN = 10
        const val BATTERY_LOW_MAX = 60
        const val BATTERY_CRITICAL_MIN = 5
        const val BATTERY_STEP = 5

        /** Critical band range for a given low band: at least one step below it. */
        fun criticalRange(lowPct: Int): IntRange = BATTERY_CRITICAL_MIN..(lowPct - BATTERY_STEP).coerceAtLeast(BATTERY_CRITICAL_MIN)
        private val BATT_LOW = intPreferencesKey("cockpit.battery.lowPct")
        private val BATT_CRIT = intPreferencesKey("cockpit.battery.criticalPct")
        private val AUDIO = booleanPreferencesKey("cockpit.audio.callouts")
        private val MODE_CALLOUTS = booleanPreferencesKey("cockpit.audio.modeCallouts")
        private val MAX_BRIGHTNESS = booleanPreferencesKey("cockpit.maxBrightness")
        private val ALT_REF = stringPreferencesKey("cockpit.hud.altitudeReference")
        private val DENSITY = stringPreferencesKey("cockpit.density")
        private val TELEMETRY_PANE = booleanPreferencesKey("cockpit.telemetryPane")
        private val VOICE_PROMPTS = booleanPreferencesKey("cockpit.audio.voicePrompts")
        private fun layersKey(vehicleId: String) = stringPreferencesKey("cockpit.layers.$vehicleId")
        private fun splitKey(vehicleId: String) = floatPreferencesKey("cockpit.splitRatio.$vehicleId")
        private fun viewKey(vehicleId: String) = stringPreferencesKey("cockpit.view.$vehicleId")
        private fun minimapKey(vehicleId: String) = stringPreferencesKey("cockpit.minimap.mode.$vehicleId")
        const val MINIMAP_AUTO = "auto"
        const val MINIMAP_LOCAL = "local"
        const val MINIMAP_MAP = "map"
    }
}
