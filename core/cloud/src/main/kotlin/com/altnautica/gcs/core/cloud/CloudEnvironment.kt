package com.altnautica.gcs.core.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Build-time cloud settings. The application binds this from its generated
 * build config; an empty [convexUrl] means this build has no cloud backend,
 * so the cloud screen says so and every cloud reach is blocked.
 */
interface CloudBuildConfig {
    val convexUrl: String
}

val CloudBuildConfig.configured: Boolean get() = convexUrl.isNotBlank()

/** Wall-clock milliseconds (server timestamps such as grant expiry are wall clock). */
fun interface WallClock {
    fun nowMs(): Long

    companion object {
        val System = WallClock { java.lang.System.currentTimeMillis() }
    }
}

/** The process-lifetime scope cloud work runs in. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudScope

/**
 * The operator's opt-in. Nothing in the cloud layer connects until this is on
 * and the operator has signed in.
 */
@Singleton
class CloudSettings @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }.distinctUntilChanged()

    suspend fun setEnabled(on: Boolean) {
        store.edit { it[ENABLED] = on }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("cloud_enabled")
    }
}
