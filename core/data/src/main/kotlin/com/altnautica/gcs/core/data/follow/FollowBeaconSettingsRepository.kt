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

/** The operator's follow beacon choices. */
data class FollowBeaconSettings(
    /** Share this phone's position with follow-me. Off until the operator turns it on. */
    val sharePosition: Boolean = false,
    /** How long sharing survives the app in the background, seconds. */
    val backgroundGraceS: Int = DEFAULT_BACKGROUND_GRACE_S,
) {
    companion object {
        const val DEFAULT_BACKGROUND_GRACE_S = 30
        const val MIN_BACKGROUND_GRACE_S = 0
        const val MAX_BACKGROUND_GRACE_S = 300
    }
}

/** Follow beacon preferences in the app settings store. */
@Singleton
class FollowBeaconSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<FollowBeaconSettings> = dataStore.data.map { p ->
        val d = FollowBeaconSettings()
        FollowBeaconSettings(
            sharePosition = p[SHARE] ?: d.sharePosition,
            backgroundGraceS = (p[GRACE_S] ?: d.backgroundGraceS).coerceIn(
                FollowBeaconSettings.MIN_BACKGROUND_GRACE_S,
                FollowBeaconSettings.MAX_BACKGROUND_GRACE_S,
            ),
        )
    }.distinctUntilChanged()

    suspend fun setSharePosition(on: Boolean) = dataStore.edit { it[SHARE] = on }

    suspend fun setBackgroundGraceS(seconds: Int) = dataStore.edit {
        it[GRACE_S] = seconds.coerceIn(FollowBeaconSettings.MIN_BACKGROUND_GRACE_S, FollowBeaconSettings.MAX_BACKGROUND_GRACE_S)
    }

    private companion object {
        val SHARE = booleanPreferencesKey("follow.beacon.share")
        val GRACE_S = intPreferencesKey("follow.beacon.backgroundGraceS")
    }
}
