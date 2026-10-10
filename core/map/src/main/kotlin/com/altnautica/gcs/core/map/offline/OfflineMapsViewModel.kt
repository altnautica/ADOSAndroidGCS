package com.altnautica.gcs.core.map.offline

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.map.TileBounds
import com.altnautica.gcs.core.map.TileEstimate
import com.altnautica.gcs.core.map.TileMath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Area being configured for download; [bounds] follows the map viewport. */
data class PackDraft(
    val name: String = "",
    val provider: MapProvider = MapProvider.Osm,
    val bounds: TileBounds? = null,
    val minZoom: Int = 12,
    val maxZoom: Int = 16,
) {
    val estimate: TileEstimate? get() = bounds?.let { TileMath.estimate(it, minZoom, maxZoom, provider) }

    val tooLarge: Boolean get() = (estimate?.tileCount ?: 0) > OfflineMapsViewModel.MAX_TILES

    val canDownload: Boolean get() = name.isNotBlank() && bounds != null && minZoom <= maxZoom && !tooLarge
}

/** One-shot outcome surfaced to the operator. */
sealed interface OfflineMapsMessage {
    data class DownloadFailed(val reason: String) : OfflineMapsMessage

    data class DeleteFailed(val reason: String) : OfflineMapsMessage

    data class Imported(val name: String) : OfflineMapsMessage

    data class ImportFailed(val error: PmtilesImportError) : OfflineMapsMessage
}

data class OfflineMapsUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val packs: List<OfflinePack> = emptyList(),
    val pmtiles: List<PmtilesPack> = emptyList(),
    val draft: PackDraft? = null,
    val importing: Boolean = false,
    val message: OfflineMapsMessage? = null,
)

@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val repository: OfflineMapRepository,
) : ViewModel() {

    private val local = MutableStateFlow(OfflineMapsUiState())

    val state: StateFlow<OfflineMapsUiState> =
        combine(local, repository.packs, repository.pmtiles) { s, packs, pmtiles -> s.copy(packs = packs, pmtiles = pmtiles) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineMapsUiState())

    init {
        refresh()
    }

    fun refresh() {
        local.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val result = repository.refresh()
            local.update { it.copy(loading = false, loadError = result.exceptionOrNull()?.let(::reason)) }
        }
    }

    fun startDraft() = local.update { it.copy(draft = PackDraft()) }

    fun cancelDraft() = local.update { it.copy(draft = null) }

    fun setDraftName(name: String) = updateDraft { it.copy(name = name) }

    fun setDraftProvider(provider: MapProvider) = updateDraft {
        it.copy(provider = provider, maxZoom = it.maxZoom.coerceAtMost(provider.maxZoom), minZoom = it.minZoom.coerceAtMost(provider.maxZoom))
    }

    fun setDraftZoom(minZoom: Int, maxZoom: Int) = updateDraft { d ->
        val maxAllowed = d.provider.maxZoom
        val min = minZoom.coerceIn(0, maxAllowed)
        d.copy(minZoom = min, maxZoom = maxZoom.coerceIn(min, maxAllowed))
    }

    fun setDraftBounds(bounds: TileBounds?) = updateDraft { it.copy(bounds = bounds) }

    fun download() {
        val draft = local.value.draft ?: return
        val bounds = draft.bounds ?: return
        if (!draft.canDownload) return
        local.update { it.copy(draft = null) }
        viewModelScope.launch {
            repository.download(draft.name.trim(), draft.provider, bounds, draft.minZoom, draft.maxZoom)
                .onFailure { e -> local.update { it.copy(message = OfflineMapsMessage.DownloadFailed(reason(e))) } }
        }
    }

    fun setPaused(id: Long, paused: Boolean) = repository.setPaused(id, paused)

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id).onFailure { e -> local.update { it.copy(message = OfflineMapsMessage.DeleteFailed(reason(e))) } }
        }
    }

    fun importPmtiles(uri: Uri) {
        local.update { it.copy(importing = true) }
        viewModelScope.launch {
            val result = repository.importPmtiles(uri)
            val message = result.fold(
                onSuccess = { OfflineMapsMessage.Imported(it.name) },
                onFailure = { e ->
                    OfflineMapsMessage.ImportFailed((e as? PmtilesImportException)?.error ?: PmtilesImportError.Io(reason(e)))
                },
            )
            local.update { it.copy(importing = false, message = message) }
        }
    }

    fun deletePmtiles(id: String) {
        viewModelScope.launch {
            repository.deletePmtiles(id).onFailure { e -> local.update { it.copy(message = OfflineMapsMessage.DeleteFailed(reason(e))) } }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun updateDraft(transform: (PackDraft) -> PackDraft) =
        local.update { s -> s.draft?.let { s.copy(draft = transform(it)) } ?: s }

    private fun reason(e: Throwable) = e.message ?: e.javaClass.simpleName

    companion object {
        /** Largest pack the screen lets an operator request in one download. */
        const val MAX_TILES = 100_000L
    }
}
