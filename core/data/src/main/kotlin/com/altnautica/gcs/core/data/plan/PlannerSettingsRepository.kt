package com.altnautica.gcs.core.data.plan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Planner preferences.
 *
 * [basemap] is the last chosen map background, stored as `online:<providerId>` or
 * `pmtiles:<packId>`; null when none was chosen yet. The planner decodes it and falls
 * back to its default when the provider or pack no longer exists.
 */
data class PlannerSettings(val basemap: String? = null)

/** Planner preferences in the app settings store. */
@Singleton
class PlannerSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<PlannerSettings> = dataStore.data.map { p -> PlannerSettings(basemap = p[BASEMAP]) }.distinctUntilChanged()

    suspend fun setBasemap(encoded: String) {
        dataStore.edit { it[BASEMAP] = encoded }
    }

    private companion object {
        val BASEMAP = stringPreferencesKey("plan.basemap")
    }
}
