package com.altnautica.gcs.core.skills.tips

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tips this device has dismissed for good and how often the flight screen has opened,
 * in the app settings store. Settings' "Reset tips" clears both.
 */
@Singleton
class TipsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val state: Flow<TipsState> = dataStore.data.map { p ->
        TipsState(seen = p[SEEN].orEmpty().sorted(), openCount = p[OPEN_COUNT] ?: 0)
    }.distinctUntilChanged()

    suspend fun markSeen(id: String) {
        dataStore.edit { it[SEEN] = it[SEEN].orEmpty() + id }
    }

    suspend fun noteFlightScreenOpen() {
        dataStore.edit { it[OPEN_COUNT] = (it[OPEN_COUNT] ?: 0) + 1 }
    }

    suspend fun reset() {
        dataStore.edit {
            it.remove(SEEN)
            it.remove(OPEN_COUNT)
        }
    }

    private companion object {
        val SEEN = stringSetPreferencesKey("cockpit.tips.seen")
        val OPEN_COUNT = intPreferencesKey("cockpit.tips.openCount")
    }
}
