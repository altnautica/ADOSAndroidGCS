package com.altnautica.gcs.ui.configure

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.mavlink.ParameterManager
import com.altnautica.gcs.data.mavlink.ParameterManager.ParamEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Groups parameters by prefix (text before first underscore).
 * Each group is sorted alphabetically by param name.
 */
data class ParamGroup(
    val prefix: String,
    val params: List<ParamEntry>,
)

/** Outcome of a write operation, surfaced as a one-shot event. */
sealed class WriteResult {
    data class Success(val paramName: String) : WriteResult()
    data class Error(val paramName: String, val message: String) : WriteResult()
}

/** Outcome of a profile save/load operation. */
sealed class ProfileResult {
    data class Saved(val fileName: String) : ProfileResult()
    data class Loaded(val fileName: String, val count: Int) : ProfileResult()
    data class Error(val message: String) : ProfileResult()
}

@HiltViewModel
class ParameterViewModel @Inject constructor(
    private val parameterManager: ParameterManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "ParameterViewModel"
        private const val PROFILES_DIR = "param_profiles"
    }

    private val gson = Gson()

    /** Raw params from the manager. */
    val params: StateFlow<Map<String, ParamEntry>> = parameterManager.params

    /** Download progress (0.0 to 1.0). */
    val downloadProgress: StateFlow<Float> = parameterManager.downloadProgress

    /** Download status. */
    val downloadStatus: StateFlow<ParameterManager.DownloadStatus> = parameterManager.downloadStatus

    /** User search query. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Collapsed group prefixes. */
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    val collapsedGroups: StateFlow<Set<String>> = _collapsedGroups.asStateFlow()

    /** One-shot write result event. */
    private val _writeResult = MutableStateFlow<WriteResult?>(null)
    val writeResult: StateFlow<WriteResult?> = _writeResult.asStateFlow()

    /** One-shot profile result event. */
    private val _profileResult = MutableStateFlow<ProfileResult?>(null)
    val profileResult: StateFlow<ProfileResult?> = _profileResult.asStateFlow()

    /** Whether a refresh is in progress. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Filtered and grouped params, derived from params + searchQuery. */
    val filteredGroups: StateFlow<List<ParamGroup>> = combine(
        params,
        _searchQuery,
    ) { paramMap, query ->
        val filtered = if (query.isBlank()) {
            paramMap.values.toList()
        } else {
            val upper = query.uppercase()
            paramMap.values.filter { it.name.uppercase().contains(upper) }
        }

        filtered
            .groupBy { entry ->
                val underscore = entry.name.indexOf('_')
                if (underscore > 0) entry.name.substring(0, underscore) else entry.name
            }
            .map { (prefix, entries) ->
                ParamGroup(
                    prefix = prefix,
                    params = entries.sortedBy { it.name },
                )
            }
            .sortedBy { it.prefix }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleGroup(prefix: String) {
        _collapsedGroups.value = _collapsedGroups.value.let { current ->
            if (prefix in current) current - prefix else current + prefix
        }
    }

    fun refreshParams() {
        viewModelScope.launch {
            _isRefreshing.value = true
            parameterManager.requestAllParams()
            _isRefreshing.value = false
        }
    }

    fun writeParam(name: String, value: Float) {
        val entry = parameterManager.getCachedParam(name)
        val type = entry?.type ?: 9 // MAV_PARAM_TYPE_REAL32
        viewModelScope.launch {
            try {
                parameterManager.writeParam(name, value, type)
                _writeResult.value = WriteResult.Success(name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write param $name: ${e.message}")
                _writeResult.value = WriteResult.Error(name, e.message ?: "Unknown error")
            }
        }
    }

    fun clearWriteResult() {
        _writeResult.value = null
    }

    fun clearProfileResult() {
        _profileResult.value = null
    }

    /**
     * Save current params to a JSON file in app-private storage.
     * File name is timestamped: params_20260402_143000.json
     */
    fun saveProfile() {
        viewModelScope.launch {
            try {
                val currentParams = params.value
                if (currentParams.isEmpty()) {
                    _profileResult.value = ProfileResult.Error("No parameters to save")
                    return@launch
                }

                val dir = File(appContext.filesDir, PROFILES_DIR)
                if (!dir.exists()) dir.mkdirs()

                val timestamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    java.util.Locale.US,
                ).format(java.util.Date())
                val fileName = "params_$timestamp.json"
                val file = File(dir, fileName)

                // Serialize as name -> value map for simplicity
                val serializable = currentParams.mapValues { (_, entry) ->
                    mapOf(
                        "value" to entry.value,
                        "type" to entry.type,
                    )
                }

                withContext(Dispatchers.IO) {
                    file.writeText(gson.toJson(serializable))
                }

                Log.i(TAG, "Saved profile: $fileName (${currentParams.size} params)")
                _profileResult.value = ProfileResult.Saved(fileName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save profile: ${e.message}")
                _profileResult.value = ProfileResult.Error("Save failed: ${e.message}")
            }
        }
    }

    /**
     * Load the most recent profile and write all params to the FC.
     */
    fun loadProfile() {
        viewModelScope.launch {
            try {
                val dir = File(appContext.filesDir, PROFILES_DIR)
                if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
                    _profileResult.value = ProfileResult.Error("No saved profiles found")
                    return@launch
                }

                val latest = dir.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.maxByOrNull { it.lastModified() }

                if (latest == null) {
                    _profileResult.value = ProfileResult.Error("No saved profiles found")
                    return@launch
                }

                val json = withContext(Dispatchers.IO) { latest.readText() }
                val mapType = object : TypeToken<Map<String, Map<String, Number>>>() {}.type
                val loaded: Map<String, Map<String, Number>> = gson.fromJson(json, mapType)

                var count = 0
                for ((name, data) in loaded) {
                    val value = data["value"]?.toFloat() ?: continue
                    val type = data["type"]?.toInt() ?: 9
                    parameterManager.writeParam(name, value, type)
                    count++
                }

                Log.i(TAG, "Loaded profile: ${latest.name} ($count params written)")
                _profileResult.value = ProfileResult.Loaded(latest.name, count)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load profile: ${e.message}")
                _profileResult.value = ProfileResult.Error("Load failed: ${e.message}")
            }
        }
    }
}
