package com.altnautica.gcs.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** App-wide appearance preferences. */
data class AppearanceSettings(
    val theme: AdosThemeId = AdosThemeId.BrandDark,
    /** Touch targets ×1.25 for gloved hands (CS-8). */
    val gloveMode: Boolean = false,
)

@Singleton
class AppSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val appearance: Flow<AppearanceSettings> = dataStore.data.map { p ->
        AppearanceSettings(
            theme = p[THEME]?.let { runCatching { AdosThemeId.valueOf(it) }.getOrNull() } ?: AdosThemeId.BrandDark,
            gloveMode = p[GLOVE] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setTheme(theme: AdosThemeId) = dataStore.edit { it[THEME] = theme.name }

    suspend fun setGloveMode(on: Boolean) = dataStore.edit { it[GLOVE] = on }

    private companion object {
        val THEME = stringPreferencesKey("app.theme")
        val GLOVE = booleanPreferencesKey("app.gloveMode")
    }
}
