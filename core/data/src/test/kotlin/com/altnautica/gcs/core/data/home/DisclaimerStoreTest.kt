package com.altnautica.gcs.core.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Prefs : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** The app stays behind the safety disclaimer until its current version is accepted. */
class DisclaimerStoreTest {
    @Test
    fun `a first launch has not accepted it`() = runBlocking {
        assertFalse(DisclaimerStore(Prefs()).accepted.first())
    }

    @Test
    fun `accepting persists the current version`() = runBlocking {
        val prefs = Prefs()
        DisclaimerStore(prefs).accept()
        assertTrue(DisclaimerStore(prefs).accepted.first())
    }

    @Test
    fun `an older accepted version shows it again`() = runBlocking {
        val prefs = Prefs()
        prefs.edit { it[intPreferencesKey("disclaimer.version")] = DisclaimerStore.CURRENT_VERSION - 1 }
        assertFalse(DisclaimerStore(prefs).accepted.first())
    }
}
