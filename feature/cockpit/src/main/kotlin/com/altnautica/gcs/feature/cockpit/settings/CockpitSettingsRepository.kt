package com.altnautica.gcs.feature.cockpit.settings

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

/** Cockpit density (CS-10). */
enum class CockpitDensity { Min, Std, Full }

/** Operator battery bands: below [lowPct] warns, below [criticalPct] is critical. */
data class BatteryBands(val lowPct: Int, val criticalPct: Int) {
    companion object {
        val DEFAULT = BatteryBands(lowPct = 30, criticalPct = 20)
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
    /** Video share of a medium/expanded split, 0.2..0.8. */
    val splitRatio: Float = 0.6f,
    /** Telemetry side pane on expanded widths. */
    val telemetryPane: Boolean = true,
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
            splitRatio = (p[SPLIT] ?: d.splitRatio).coerceIn(MIN_SPLIT, MAX_SPLIT),
            telemetryPane = p[TELEMETRY_PANE] ?: d.telemetryPane,
        )
    }.distinctUntilChanged()

    /** Sets both bands; the critical band is kept below the low band. */
    suspend fun setBatteryBands(lowPct: Int, criticalPct: Int) = dataStore.edit {
        val low = lowPct.coerceIn(5, 95)
        it[BATT_LOW] = low
        it[BATT_CRIT] = criticalPct.coerceIn(1, low - 1)
    }

    suspend fun setAudioCallouts(on: Boolean) = dataStore.edit { it[AUDIO] = on }

    suspend fun setModeCallouts(on: Boolean) = dataStore.edit { it[MODE_CALLOUTS] = on }

    suspend fun setMaxBrightnessDefault(on: Boolean) = dataStore.edit { it[MAX_BRIGHTNESS] = on }

    suspend fun setAltitudeReference(ref: AltitudeReference) = dataStore.edit { it[ALT_REF] = ref.name }

    suspend fun setDensity(density: CockpitDensity) = dataStore.edit { it[DENSITY] = density.name }

    suspend fun setSplitRatio(ratio: Float) = dataStore.edit { it[SPLIT] = ratio.coerceIn(MIN_SPLIT, MAX_SPLIT) }

    suspend fun setTelemetryPane(on: Boolean) = dataStore.edit { it[TELEMETRY_PANE] = on }

    companion object {
        const val MIN_SPLIT = 0.2f
        const val MAX_SPLIT = 0.8f
        private val BATT_LOW = intPreferencesKey("cockpit.battery.lowPct")
        private val BATT_CRIT = intPreferencesKey("cockpit.battery.criticalPct")
        private val AUDIO = booleanPreferencesKey("cockpit.audio.callouts")
        private val MODE_CALLOUTS = booleanPreferencesKey("cockpit.audio.modeCallouts")
        private val MAX_BRIGHTNESS = booleanPreferencesKey("cockpit.maxBrightness")
        private val ALT_REF = stringPreferencesKey("cockpit.hud.altitudeReference")
        private val DENSITY = stringPreferencesKey("cockpit.density")
        private val SPLIT = floatPreferencesKey("cockpit.splitRatio")
        private val TELEMETRY_PANE = booleanPreferencesKey("cockpit.telemetryPane")
    }
}
