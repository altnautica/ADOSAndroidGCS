package com.altnautica.gcs.core.extensions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.extensions.data.ApiResult
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.ExtensionsState
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.extensions.manifest.CatalogEntry
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject

/** Install in progress: the entry and the agent's latest stage. */
data class InstallProgress(val entry: CatalogEntry, val stage: String?)

data class NodeExtensionsUiState(
    val extensions: ExtensionsState = ExtensionsState.Loading,
    /** Catalog entries not yet installed; null while unknown. */
    val catalog: List<CatalogEntry>? = null,
    val busy: Set<String> = emptySet(),
    val install: InstallProgress? = null,
    /** The last write's result, shown once in the screen's snackbar, then cleared. */
    val notice: ExtensionNotice? = null,
)

/** What the operator hears about a finished write. */
sealed interface ExtensionNotice {
    data class Installed(val name: String) : ExtensionNotice
    data class Uninstalled(val name: String) : ExtensionNotice

    /** [outcome] is a [WriteOutcome.NoPath] or [WriteOutcome.Failed]. */
    data class Problem(val outcome: WriteOutcome) : ExtensionNotice

    companion object {
        /** Null for a quiet success (enable, grant): the switch itself shows the result. */
        fun of(outcome: WriteOutcome, success: ExtensionNotice?): ExtensionNotice? =
            if (outcome == WriteOutcome.Done) success else Problem(outcome)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class NodeExtensionsViewModel @Inject constructor(
    private val repository: ExtensionRepository,
) : ViewModel() {

    private val node = MutableStateFlow<DeviceId?>(null)
    private val catalog = MutableStateFlow<List<CatalogEntry>?>(null)
    private val local = MutableStateFlow(NodeExtensionsUiState())

    val state: StateFlow<NodeExtensionsUiState> = node.flatMapLatest { n ->
        if (n == null) flowOf(ExtensionsState.Loading) else repository.extensions(n)
    }.let { ext ->
        combine(ext, catalog, local) { e, c, l ->
            val installed = (e as? ExtensionsState.Loaded)?.extensions.orEmpty().map { it.pluginId }.toSet()
            l.copy(extensions = e, catalog = c?.filter { it.id !in installed })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeExtensionsUiState())

    fun start(id: DeviceId) {
        if (node.value == id) return
        node.value = id
        refresh()
    }

    fun refresh() {
        val n = node.value ?: return
        viewModelScope.launch {
            repository.refresh(n)
            if (repository.extensions(n).value is ExtensionsState.Loaded) {
                catalog.value = (repository.catalog(n) as? ApiResult.Ok)?.value
            }
        }
    }

    fun setEnabled(pluginId: String, enabled: Boolean) = run(pluginId, success = null) { n -> repository.setEnabled(n, pluginId, enabled) }

    fun setGranted(pluginId: String, permission: String, granted: Boolean) = run("$pluginId/$permission", success = null) { n ->
        if (granted) repository.grant(n, pluginId, permission) else repository.revoke(n, pluginId, permission)
    }

    /** [name] is the extension's display name for the result line; the screen confirms first. */
    fun uninstall(pluginId: String, name: String) =
        run(pluginId, success = ExtensionNotice.Uninstalled(name)) { n -> repository.uninstall(n, pluginId) }

    fun install(entry: CatalogEntry) {
        val n = node.value ?: return
        if (local.value.install != null) return
        val jobId = "and-" + UUID.randomUUID()
        local.update { it.copy(install = InstallProgress(entry, null), notice = null) }
        val progress = viewModelScope.launch {
            val api = repository.api(n) ?: return@launch
            try {
                api.installJob(jobId).collect { text ->
                    val stage = runCatching {
                        ((Json.parseToJsonElement(text) as? JsonObject)?.get("stage") as? JsonPrimitive)?.content
                    }.getOrNull()
                    if (stage != null) local.update { s -> s.copy(install = s.install?.copy(stage = stage)) }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // The stream is advisory; the install call's own answer is authoritative.
            }
        }
        viewModelScope.launch {
            val outcome = repository.install(n, entry, jobId)
            progress.cancel()
            local.update { it.copy(install = null, notice = ExtensionNotice.of(outcome, ExtensionNotice.Installed(entry.name))) }
            if (outcome == WriteOutcome.Done) catalog.value = (repository.catalog(n) as? ApiResult.Ok)?.value
        }
    }

    fun clearNotice() = local.update { it.copy(notice = null) }

    private fun run(busyKey: String, success: ExtensionNotice?, op: suspend (DeviceId) -> WriteOutcome) {
        val n = node.value ?: return
        local.update { it.copy(busy = it.busy + busyKey, notice = null) }
        viewModelScope.launch {
            val outcome = op(n)
            local.update { it.copy(busy = it.busy - busyKey, notice = ExtensionNotice.of(outcome, success)) }
        }
    }
}
