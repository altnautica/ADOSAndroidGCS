package com.altnautica.gcs.data.mission

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.data.agriculture.Waypoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SavedMission(
    val name: String,
    val waypoints: List<Waypoint>,
    val createdAt: Long = System.currentTimeMillis(),
)

@Singleton
class MissionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        private const val TAG = "MissionRepository"
        private val MISSIONS_KEY = stringPreferencesKey("saved_missions")
    }

    private val gson = Gson()

    private val _savedMissions = MutableStateFlow<List<SavedMission>>(emptyList())
    val savedMissions: StateFlow<List<SavedMission>> = _savedMissions.asStateFlow()

    suspend fun loadMissions() {
        val prefs = dataStore.data.first()
        val json = prefs[MISSIONS_KEY] ?: "[]"
        val type = object : TypeToken<List<SavedMission>>() {}.type
        _savedMissions.value = try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved missions: ${e.message}")
            emptyList()
        }
    }

    suspend fun saveMission(mission: SavedMission) {
        val updated = _savedMissions.value.filter { it.name != mission.name } + mission
        _savedMissions.value = updated
        persist(updated)
        Log.i(TAG, "Saved mission '${mission.name}' with ${mission.waypoints.size} waypoints")
    }

    suspend fun deleteMission(name: String) {
        val updated = _savedMissions.value.filter { it.name != name }
        _savedMissions.value = updated
        persist(updated)
        Log.i(TAG, "Deleted mission '$name'")
    }

    fun getMission(name: String): SavedMission? =
        _savedMissions.value.find { it.name == name }

    private suspend fun persist(missions: List<SavedMission>) {
        dataStore.edit { prefs ->
            prefs[MISSIONS_KEY] = gson.toJson(missions)
        }
    }
}
