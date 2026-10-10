package com.altnautica.gcs.core.map.offline

import android.net.Uri
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.map.TileBounds
import kotlinx.coroutines.flow.StateFlow

/** Download progress of one offline pack. */
data class OfflinePackStatus(
    val downloading: Boolean,
    val complete: Boolean,
    val completedResources: Long,
    val requiredResources: Long,
    val completedBytes: Long,
    /** Last download error reported for the pack, or null. */
    val error: String?,
) {
    /** 0..1, or null while the required resource count is still unknown. */
    val fraction: Float?
        get() = if (requiredResources > 0) (completedResources.toFloat() / requiredResources).coerceIn(0f, 1f) else null
}

/** A downloaded (or downloading) area of one online provider. */
data class OfflinePack(
    val id: Long,
    val name: String,
    val provider: MapProvider,
    val bounds: TileBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val status: OfflinePackStatus,
)

/** Why a PMTiles import was refused. */
sealed interface PmtilesImportError {
    data object NotPmtiles : PmtilesImportError

    data object VectorTiles : PmtilesImportError

    data class Io(val message: String) : PmtilesImportError
}

class PmtilesImportException(val error: PmtilesImportError) : Exception(error.toString())

/** Offline basemaps: tile-pyramid packs downloaded from a provider and imported PMTiles archives. */
interface OfflineMapRepository {
    /** Downloaded packs, newest first. */
    val packs: StateFlow<List<OfflinePack>>

    /** Imported raster PMTiles archives. */
    val pmtiles: StateFlow<List<PmtilesPack>>

    /** Reloads both lists from storage. */
    suspend fun refresh(): Result<Unit>

    /** Creates a pack for [bounds] over [minZoom]..[maxZoom] and starts downloading it. */
    suspend fun download(name: String, provider: MapProvider, bounds: TileBounds, minZoom: Int, maxZoom: Int): Result<Unit>

    fun setPaused(id: Long, paused: Boolean)

    suspend fun delete(id: Long): Result<Unit>

    /** Copies the archive behind [uri] into app storage; fails with [PmtilesImportException]. */
    suspend fun importPmtiles(uri: Uri): Result<PmtilesPack>

    suspend fun deletePmtiles(id: String): Result<Unit>
}
