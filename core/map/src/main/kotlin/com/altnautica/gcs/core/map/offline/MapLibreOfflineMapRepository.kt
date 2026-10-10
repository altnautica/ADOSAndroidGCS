package com.altnautica.gcs.core.map.offline

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.MapStyle
import com.altnautica.gcs.core.map.PmtilesHeader
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.map.TileBounds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [OfflineMapRepository] on MapLibre's offline database. MapLibre requires its
 * offline API on the main thread; file work runs on the IO dispatcher.
 */
@Singleton
class MapLibreOfflineMapRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : OfflineMapRepository {

    private val _packs = MutableStateFlow<List<OfflinePack>>(emptyList())
    override val packs: StateFlow<List<OfflinePack>> = _packs.asStateFlow()

    private val _pmtiles = MutableStateFlow<List<PmtilesPack>>(emptyList())
    override val pmtiles: StateFlow<List<PmtilesPack>> = _pmtiles.asStateFlow()

    private val regions = HashMap<Long, OfflineRegion>()
    private val errors = HashMap<Long, String>()

    private val manager: OfflineManager by lazy {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context)
    }

    private val styleDir get() = File(context.filesDir, "maplibre/styles")
    private val packDir get() = File(context.filesDir, "maplibre/packs")

    override suspend fun refresh(): Result<Unit> = runCatching {
        withContext(Dispatchers.Main) {
            val listed = listRegions()
            regions.clear()
            val packs = listed.mapNotNull { region ->
                val pack = region.toPack(EMPTY_STATUS) ?: return@mapNotNull null
                regions[region.id] = region
                observe(region)
                pack
            }
            _packs.value = packs.sortedByDescending { it.id }
            listed.forEach { region -> if (regions.containsKey(region.id)) requestStatus(region) }
        }
        _pmtiles.value = withContext(Dispatchers.IO) { scanPmtiles() }
    }

    override suspend fun download(
        name: String,
        provider: MapProvider,
        bounds: TileBounds,
        minZoom: Int,
        maxZoom: Int,
    ): Result<Unit> = runCatching {
        val styleFile = withContext(Dispatchers.IO) {
            styleDir.mkdirs()
            File(styleDir, "${provider.id}.json").apply { writeText(MapStyle.baseStyleJson(provider)) }
        }
        withContext(Dispatchers.Main) {
            val definition = OfflineTilePyramidRegionDefinition(
                "file://${styleFile.absolutePath}",
                LatLngBounds.from(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon),
                minZoom.toDouble(),
                maxZoom.coerceAtMost(provider.maxZoom).toDouble(),
                context.resources.displayMetrics.density,
            )
            val metadata = encodeMetadata(name, provider)
            val region = suspendCancellableCoroutine { cont ->
                manager.createOfflineRegion(
                    definition,
                    metadata,
                    object : OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(Result.success(offlineRegion))
                        override fun onError(error: String) = cont.resume(Result.failure(IOException(error)))
                    },
                )
            }.getOrThrow()
            regions[region.id] = region
            observe(region)
            region.toPack(EMPTY_STATUS.copy(downloading = true))?.let { pack -> _packs.update { listOf(pack) + it } }
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
    }

    override fun setPaused(id: Long, paused: Boolean) {
        val region = regions[id] ?: return
        if (!paused) errors.remove(id)
        region.setDownloadState(if (paused) OfflineRegion.STATE_INACTIVE else OfflineRegion.STATE_ACTIVE)
        updateStatus(id) { it.copy(downloading = !paused, error = if (paused) it.error else null) }
    }

    override suspend fun delete(id: Long): Result<Unit> = runCatching {
        withContext(Dispatchers.Main) {
            val region = regions[id] ?: return@withContext
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            region.setObserver(null)
            suspendCancellableCoroutine { cont ->
                region.delete(
                    object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() = cont.resume(Result.success(Unit))
                        override fun onError(error: String) = cont.resume(Result.failure(IOException(error)))
                    },
                )
            }.getOrThrow()
            regions.remove(id)
            errors.remove(id)
            _packs.update { list -> list.filterNot { it.id == id } }
        }
    }

    override suspend fun importPmtiles(uri: Uri): Result<PmtilesPack> = runCatching {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "pack.pmtiles"
            val headerBytes = try {
                resolver.openInputStream(uri)?.use { input -> input.readHeader() }
                    ?: throw PmtilesImportException(PmtilesImportError.Io("unreadable document"))
            } catch (e: IOException) {
                throw PmtilesImportException(PmtilesImportError.Io(e.message ?: e.javaClass.simpleName))
            }
            val header = try {
                PmtilesHeader.parse(headerBytes)
            } catch (_: IllegalArgumentException) {
                throw PmtilesImportException(PmtilesImportError.NotPmtiles)
            }
            if (!header.tileType.raster) throw PmtilesImportException(PmtilesImportError.VectorTiles)
            packDir.mkdirs()
            val target = uniqueFile(packFileName(displayName))
            try {
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException("unreadable document")
            } catch (e: IOException) {
                target.delete()
                throw PmtilesImportException(PmtilesImportError.Io(e.message ?: e.javaClass.simpleName))
            }
            val pack = target.toPmtilesPack(header)
            _pmtiles.update { list -> (list + pack).sortedBy { it.name.lowercase() } }
            pack
        }
    }

    override suspend fun deletePmtiles(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val file = File(packDir, id)
            if (file.exists() && !file.delete()) throw IOException("could not delete $id")
            _pmtiles.update { list -> list.filterNot { it.id == id } }
        }
    }

    private suspend fun listRegions(): List<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) = cont.resume(offlineRegions?.toList().orEmpty())
                override fun onError(error: String) = cont.resumeWithException(IOException(error))
            },
        )
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(
            object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val error = errors[region.id]
                    updateStatus(region.id) { status.toStatus(error) }
                    if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                }

                override fun onError(error: OfflineRegionError) {
                    val message = error.message.ifBlank { error.reason }
                    errors[region.id] = message
                    updateStatus(region.id) { it.copy(error = message) }
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    val message = "tile limit $limit"
                    errors[region.id] = message
                    updateStatus(region.id) { it.copy(error = message, downloading = false) }
                }
            },
        )
    }

    private fun requestStatus(region: OfflineRegion) {
        region.getStatus(
            object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (status != null) updateStatus(region.id) { status.toStatus(errors[region.id]) }
                }

                override fun onError(error: String?) {
                    if (error != null) updateStatus(region.id) { it.copy(error = error) }
                }
            },
        )
    }

    private fun updateStatus(id: Long, transform: (OfflinePackStatus) -> OfflinePackStatus) {
        _packs.update { list -> list.map { if (it.id == id) it.copy(status = transform(it.status)) else it } }
    }

    private fun OfflineRegionStatus.toStatus(error: String?) = OfflinePackStatus(
        downloading = downloadState == OfflineRegion.STATE_ACTIVE && !isComplete,
        complete = isComplete,
        completedResources = completedResourceCount,
        requiredResources = requiredResourceCount,
        completedBytes = completedResourceSize,
        error = error,
    )

    private fun OfflineRegion.toPack(status: OfflinePackStatus): OfflinePack? {
        val definition = definition as? OfflineTilePyramidRegionDefinition ?: return null
        val meta = decodeMetadata(metadata) ?: return null
        val provider = MapProvider.fromId(meta.providerId) ?: return null
        val box = definition.bounds ?: return null
        return OfflinePack(
            id = id,
            name = meta.name,
            provider = provider,
            bounds = TileBounds(minLat = box.getLatSouth(), maxLat = box.getLatNorth(), minLon = box.getLonWest(), maxLon = box.getLonEast()),
            minZoom = definition.minZoom.toInt(),
            maxZoom = definition.maxZoom.toInt(),
            status = status,
        )
    }

    private fun scanPmtiles(): List<PmtilesPack> =
        packDir.listFiles { f -> f.isFile && f.name.endsWith(PMTILES_EXT) }.orEmpty().mapNotNull { file ->
            val header = runCatching { PmtilesHeader.parse(file.inputStream().use { it.readHeader() }) }.getOrNull()
            header?.takeIf { it.tileType.raster }?.let { file.toPmtilesPack(it) }
        }.sortedBy { it.name.lowercase() }

    private fun File.toPmtilesPack(header: PmtilesHeader) = PmtilesPack(
        id = name,
        name = name.removeSuffix(PMTILES_EXT),
        absolutePath = absolutePath,
        sizeBytes = length(),
        header = header,
    )

    private fun uniqueFile(fileName: String): File {
        val base = fileName.removeSuffix(PMTILES_EXT)
        var candidate = File(packDir, fileName)
        var n = 2
        while (candidate.exists()) candidate = File(packDir, "$base-${n++}$PMTILES_EXT")
        return candidate
    }

    internal companion object {
        const val PMTILES_EXT = ".pmtiles"

        val EMPTY_STATUS = OfflinePackStatus(
            downloading = false,
            complete = false,
            completedResources = 0,
            requiredResources = 0,
            completedBytes = 0,
            error = null,
        )

        data class PackMetadata(val name: String, val providerId: String)

        fun encodeMetadata(name: String, provider: MapProvider): ByteArray =
            buildJsonObject {
                put("name", name)
                put("provider", provider.id)
                put("v", 1)
            }.toString().toByteArray(Charsets.UTF_8)

        fun decodeMetadata(bytes: ByteArray?): PackMetadata? = runCatching {
            val obj: JsonObject = Json.parseToJsonElement(bytes!!.toString(Charsets.UTF_8)).jsonObject
            require(obj["v"]?.jsonPrimitive?.int == 1)
            PackMetadata(obj.getValue("name").jsonPrimitive.content, obj.getValue("provider").jsonPrimitive.content)
        }.getOrNull()

        /** File-system-safe archive name derived from a document's display name. */
        fun packFileName(displayName: String): String {
            val base = displayName.removeSuffix(PMTILES_EXT).replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "pack" }
            return base.take(64) + PMTILES_EXT
        }
    }
}

/** Reads up to the PMTiles header size; a shorter stream yields fewer bytes. */
internal fun java.io.InputStream.readHeader(): ByteArray {
    val buffer = ByteArray(PmtilesHeader.SIZE)
    var read = 0
    while (read < buffer.size) {
        val n = read(buffer, read, buffer.size - read)
        if (n < 0) break
        read += n
    }
    return buffer.copyOf(read)
}
