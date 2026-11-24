package com.altnautica.gcs.feature.cockpit.alerts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.altnautica.gcs.core.skills.hud.AlertSnooze
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The alert keys (`id|text`) the operator hid from the alert list, kept per device under
 * `alert_snooze`. A snoozed warning leaves the list and the pill count; a snoozed critical only
 * folds. [prune] drops keys whose alert has cleared, so an alert that comes back shows again.
 */
@Singleton
class AlertSnoozeStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val snoozed: Flow<Set<String>> = dataStore.data.map { it[KEY].orEmpty() }.distinctUntilChanged()

    /** Snooze [key] ([on]) or show it again. */
    suspend fun set(key: String, on: Boolean) {
        dataStore.edit { it[KEY] = AlertSnooze.with(it[KEY].orEmpty(), key, on) }
    }

    /** Drop the snoozed keys no longer among [liveKeys]; no write when none cleared. */
    suspend fun prune(liveKeys: Collection<String>) {
        dataStore.edit { prefs ->
            val now = prefs[KEY].orEmpty()
            val kept = AlertSnooze.prune(now, liveKeys)
            if (kept !== now) prefs[KEY] = kept
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("alert_snooze")
    }
}
