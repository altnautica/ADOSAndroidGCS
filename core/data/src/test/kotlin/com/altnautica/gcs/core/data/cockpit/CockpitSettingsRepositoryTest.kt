package com.altnautica.gcs.core.data.cockpit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** The split divider follows the spec (default half, clamped to 0.3..0.7) and, with the view, is kept per vehicle. */
class CockpitSettingsRepositoryTest {
    @Test
    fun `split divider defaults to half`() = runBlocking {
        assertEquals(0.5f, CockpitSettingsRepository(MemoryPreferences()).splitRatio("v1").first())
    }

    @Test
    fun `split divider is clamped to the spec range`() = runBlocking {
        val repo = CockpitSettingsRepository(MemoryPreferences())
        repo.setSplitRatio("v1", 0.1f)
        assertEquals(0.3f, repo.splitRatio("v1").first())
        repo.setSplitRatio("v1", 0.95f)
        assertEquals(0.7f, repo.splitRatio("v1").first())
    }

    @Test
    fun `split and view are kept per vehicle`() = runBlocking {
        val repo = CockpitSettingsRepository(MemoryPreferences())
        repo.setSplitRatio("v1", 0.6f)
        repo.setView("v1", "map")
        assertEquals(0.6f, repo.splitRatio("v1").first())
        assertEquals(0.5f, repo.splitRatio("v2").first())
        assertEquals("map", repo.view("v1").first())
        assertNull(repo.view("v2").first())
    }
}
