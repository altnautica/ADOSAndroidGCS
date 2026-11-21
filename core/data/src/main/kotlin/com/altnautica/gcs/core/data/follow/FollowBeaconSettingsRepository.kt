package com.altnautica.gcs.core.data.follow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Follow beacon preferences in the app settings store. */
@Singleton
class FollowBeaconSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * Whether the operator shares this phone's position with follow-me on [vehicleId]. Consent is
     * per vehicle: off for every vehicle until the operator turns it on for that one.
     */
    fun sharePosition(vehicleId: String): Flow<Boolean> =
        dataStore.data.map { it[shareKey(vehicleId)] ?: false }.distinctUntilChanged()

    suspend fun setSharePosition(vehicleId: String, on: Boolean) = dataStore.edit { it[shareKey(vehicleId)] = on }

    /** How long sharing survives the app in the background, seconds. */
    val backgroundGraceS: Flow<Int> = dataStore.data
        .map { (it[GRACE_S] ?: DEFAULT_BACKGROUND_GRACE_S).coerceIn(MIN_BACKGROUND_GRACE_S, MAX_BACKGROUND_GRACE_S) }
        .distinctUntilChanged()

    suspend fun setBackgroundGraceS(seconds: Int) = dataStore.edit {
        it[GRACE_S] = seconds.coerceIn(MIN_BACKGROUND_GRACE_S, MAX_BACKGROUND_GRACE_S)
    }

    companion object {
        const val DEFAULT_BACKGROUND_GRACE_S = 30
        const val MIN_BACKGROUND_GRACE_S = 0
        const val MAX_BACKGROUND_GRACE_S = 300

        private val GRACE_S = intPreferencesKey("follow.beacon.backgroundGraceS")
        private fun shareKey(vehicleId: String) = booleanPreferencesKey("follow.beacon.share.$vehicleId")
    }
}
