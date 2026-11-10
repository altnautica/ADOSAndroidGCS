package com.altnautica.gcs.core.data.cockpit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** The split divider follows the spec: default half, clamped to 0.3..0.7. */
class CockpitSettingsRepositoryTest {
    @Test
    fun `split divider defaults to half`() = runBlocking {
        assertEquals(0.5f, CockpitSettingsRepository(MemoryPreferences()).settings.first().splitRatio)
    }

    @Test
    fun `split divider is clamped to the spec range`() = runBlocking {
        val repo = CockpitSettingsRepository(MemoryPreferences())
        repo.setSplitRatio(0.1f)
        assertEquals(0.3f, repo.settings.first().splitRatio)
        repo.setSplitRatio(0.95f)
        assertEquals(0.7f, repo.settings.first().splitRatio)
    }
}
