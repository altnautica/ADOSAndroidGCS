package com.altnautica.gcs.core.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.core.model.DeviceId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The vehicle the console Home and the Quick menu act on, kept across launches. */
@Singleton
class SelectedVehicleStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** The stored vehicle id, or null when none was chosen yet. It may name a vehicle no longer listed. */
    val selected: Flow<DeviceId?> = dataStore.data.map { p -> p[SELECTED]?.let(::DeviceId) }.distinctUntilChanged()

    suspend fun select(id: DeviceId) {
        dataStore.edit { it[SELECTED] = id.value }
    }

    private companion object {
        val SELECTED = stringPreferencesKey("home.selectedVehicle")
    }
}
