package com.altnautica.gcs.core.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The safety disclaimer version the operator accepted. The app stays behind the disclaimer
 * until [CURRENT_VERSION] is accepted; raising the version shows it again after a text change.
 */
@Singleton
class DisclaimerStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** True once the current disclaimer version (or a later one) has been accepted. */
    val accepted: Flow<Boolean> = dataStore.data.map { (it[VERSION] ?: 0) >= CURRENT_VERSION }.distinctUntilChanged()

    suspend fun accept() {
        dataStore.edit { it[VERSION] = CURRENT_VERSION }
    }

    companion object {
        const val CURRENT_VERSION = 1
        private val VERSION = intPreferencesKey("disclaimer.version")
    }
}
