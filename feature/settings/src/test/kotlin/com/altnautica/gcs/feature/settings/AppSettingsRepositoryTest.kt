package com.altnautica.gcs.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsRepositoryTest {

    /** In-memory preferences store; the file-backed one behaves identically through this interface. */
    private class MemoryStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun defaultsThenPersistsChanges() = runTest {
        val store = MemoryStore()
        val repo = AppSettingsRepository(store)
        assertEquals(AppearanceSettings(AdosThemeId.BrandDark, gloveMode = false), repo.appearance.first())

        repo.setTheme(AdosThemeId.Nvg)
        repo.setGloveMode(true)
        assertEquals(AppearanceSettings(AdosThemeId.Nvg, gloveMode = true), repo.appearance.first())
        // A fresh repository over the same store reads the saved values.
        assertEquals(AppearanceSettings(AdosThemeId.Nvg, gloveMode = true), AppSettingsRepository(store).appearance.first())
    }

    @Test
    fun unknownStoredThemeFallsBackToDark() = runTest {
        val store = MemoryStore()
        store.edit { it[stringPreferencesKey("app.theme")] = "Sepia" }
        assertEquals(AdosThemeId.BrandDark, AppSettingsRepository(store).appearance.first().theme)
    }
}
