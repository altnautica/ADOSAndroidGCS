package com.altnautica.gcs.core.data.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.altnautica.gcs.core.model.DeviceId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class ControllerSettings(
    /** Controller rumble for confirmations and alerts. */
    val rumble: Boolean = true,
    /** Light bar status colours. */
    val lightbar: Boolean = true,
    /** Stick dead zone added to the hardware flat range, 0..20 %. */
    val deadZonePct: Int = 0,
    /** Pitch, roll and yaw expo, 0..60 %. */
    val expoPct: Int = 0,
    /** Vehicles (`DeviceId.value`) whose stick control is allowed in every flight mode. */
    val allModesVehicles: Set<String> = emptySet(),
)

/** Game controller preferences in the app settings store. */
@Singleton
class ControllerSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<ControllerSettings> = dataStore.data.map { p ->
        val d = ControllerSettings()
        ControllerSettings(
            rumble = p[RUMBLE] ?: d.rumble,
            lightbar = p[LIGHTBAR] ?: d.lightbar,
            deadZonePct = (p[DEAD_ZONE] ?: d.deadZonePct).coerceIn(0, MAX_DEAD_ZONE_PCT),
            expoPct = (p[EXPO] ?: d.expoPct).coerceIn(0, MAX_EXPO_PCT),
            allModesVehicles = p[ALL_MODES] ?: d.allModesVehicles,
        )
    }.distinctUntilChanged()

    suspend fun setRumble(on: Boolean) = dataStore.edit { it[RUMBLE] = on }

    suspend fun setLightbar(on: Boolean) = dataStore.edit { it[LIGHTBAR] = on }

    suspend fun setDeadZonePct(pct: Int) = dataStore.edit { it[DEAD_ZONE] = pct.coerceIn(0, MAX_DEAD_ZONE_PCT) }

    suspend fun setExpoPct(pct: Int) = dataStore.edit { it[EXPO] = pct.coerceIn(0, MAX_EXPO_PCT) }

    /** Allows or withdraws stick control in every flight mode for [vehicle]. */
    suspend fun setAllModes(vehicle: DeviceId, enabled: Boolean) = dataStore.edit {
        val current = it[ALL_MODES] ?: emptySet()
        it[ALL_MODES] = if (enabled) current + vehicle.value else current - vehicle.value
    }

    companion object {
        const val MAX_DEAD_ZONE_PCT = 20
        const val MAX_EXPO_PCT = 60
        private val RUMBLE = booleanPreferencesKey("controller.rumble")
        private val LIGHTBAR = booleanPreferencesKey("controller.lightbar")
        private val DEAD_ZONE = intPreferencesKey("controller.deadZonePct")
        private val EXPO = intPreferencesKey("controller.expoPct")
        private val ALL_MODES = stringSetPreferencesKey("controller.allModesVehicles")
    }
}
