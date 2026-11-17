package com.altnautica.gcs.core.data.controller

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** In-memory preferences store; the file-backed one cannot rename atomically on Windows under test. */
private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

class ControllerSettingsRepositoryTest {
    @Test
    fun `defaults apply until a value is written`() = runBlocking {
        val repo = ControllerSettingsRepository(MemoryPreferences())
        assertEquals(ControllerSettings(), repo.settings.first())
    }

    @Test
    fun `expo is clamped to its range`() = runBlocking {
        val repo = ControllerSettingsRepository(MemoryPreferences())
        repo.setExpoPct(-5)
        assertEquals(0, repo.settings.first().expoPct)
        repo.setExpoPct(90)
        assertEquals(ControllerSettingsRepository.MAX_EXPO_PCT, repo.settings.first().expoPct)
    }

    @Test
    fun `all-modes vehicles are added and removed by id`() = runBlocking {
        val repo = ControllerSettingsRepository(MemoryPreferences())
        repo.setAllModes(DeviceId("drone-1"), true)
        repo.setAllModes(DeviceId("drone-2"), true)
        repo.setAllModes(DeviceId("drone-1"), false)
        assertEquals(setOf("drone-2"), repo.settings.first().allModesVehicles)
    }
}
