package com.altnautica.gcs.feature.cockpit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.altnautica.gcs.core.data.follow.FollowBeaconSettingsRepository
import com.altnautica.gcs.core.link.follow.BeaconFix
import com.altnautica.gcs.core.link.follow.FollowBeaconControllerFactory
import com.altnautica.gcs.core.link.follow.PhonePosition
import com.altnautica.gcs.core.link.follow.RouterBeaconStatus
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A phone with no location permission, always in front. */
private object NoPhonePosition : PhonePosition {
    override fun hasPermission(): Boolean = false
    override fun fixes(): Flow<BeaconFix> = emptyFlow()
    override fun nowMs(): Long = 0L
    override val foreground: StateFlow<Boolean> = MutableStateFlow(true)
}

/** A router that cannot be asked. */
private object NoRouter : RouterBeaconStatus {
    override suspend fun published(vehicle: DeviceId, reach: Reach): Long? = null
}

/** The follow beacon of a test cockpit: sharing off, no location, no router to ask. */
fun testBeaconFactory(dataStore: DataStore<Preferences>): FollowBeaconControllerFactory =
    FollowBeaconControllerFactory(NoPhonePosition, FollowBeaconSettingsRepository(dataStore), NoRouter)
