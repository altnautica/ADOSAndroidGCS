package com.altnautica.gcs.core.demo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.altnautica.gcs.core.model.DeviceId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The operator's demo-mode switch. */
@Singleton
class DemoSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }.distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    /** The demo pod stops reporting one skill's state, so its slot goes stale ("last seen"). */
    val staleSkill: Flow<Boolean> = dataStore.data.map { it[STALE_SKILL] ?: false }.distinctUntilChanged()

    suspend fun setStaleSkill(stale: Boolean) {
        dataStore.edit { it[STALE_SKILL] = stale }
    }

    /** Turns demo mode on and returns the demo vehicle to open. */
    suspend fun enable(): DeviceId {
        setEnabled(true)
        return DemoIds.DRONE
    }

    private companion object {
        // Key kept from the earlier direct-link setting so a stored choice survives.
        val ENABLED = booleanPreferencesKey("direct_link_demo_enabled")
        val STALE_SKILL = booleanPreferencesKey("demo_stale_skill")
    }
}
