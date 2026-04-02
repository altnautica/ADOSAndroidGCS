package com.altnautica.gcs.data.maps

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class TileBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

data class TileEstimate(
    val tileCount: Int,
    val estimatedSizeMb: Float,
)

data class TilePack(
    val id: String,
    val name: String,
    val bounds: TileBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Int,
    val sizeMb: Float,
    val createdAt: Long,
)

@Singleton
class TileDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "TileDownloadManager"
        private const val TILE_URL_TEMPLATE =
            "https://tile.openstreetmap.org/%d/%d/%d.png"
        private const val AVG_TILE_SIZE_KB = 15 // average tile size for estimation
        private const val TILE_DIR = "offline_tiles"
        private const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500MB
    }

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _downloadedPacks = MutableStateFlow<List<TilePack>>(emptyList())
    val downloadedPacks: StateFlow<List<TilePack>> = _downloadedPacks.asStateFlow()

    private var cancelled = false

    private val tileDir: File
        get() = File(context.filesDir, TILE_DIR).also { it.mkdirs() }

    fun estimateDownload(bounds: TileBounds, minZoom: Int, maxZoom: Int): TileEstimate {
        var count = 0
        for (z in minZoom..maxZoom) {
            val xRange = tileXRange(bounds.minLon, bounds.maxLon, z)
            val yRange = tileYRange(bounds.minLat, bounds.maxLat, z)
            count += (xRange.last - xRange.first + 1) * (yRange.last - yRange.first + 1)
        }
        val sizeMb = count * AVG_TILE_SIZE_KB / 1024f
        return TileEstimate(count, sizeMb)
    }

    suspend fun startDownload(
        bounds: TileBounds,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        _downloading.value = true
        _progress.value = 0f

        val estimate = estimateDownload(bounds, minZoom, maxZoom)
        val packId = "pack_${System.currentTimeMillis()}"
        val packDir = File(tileDir, packId).also { it.mkdirs() }

        var downloaded = 0
        var totalBytes = 0L

        for (z in minZoom..maxZoom) {
            val xRange = tileXRange(bounds.minLon, bounds.maxLon, z)
            val yRange = tileYRange(bounds.minLat, bounds.maxLat, z)

            for (x in xRange) {
                for (y in yRange) {
                    if (cancelled || !coroutineContext.isActive) {
                        _downloading.value = false
                        return@withContext
                    }

                    val tileFile = File(packDir, "$z/$x/$y.png")
                    tileFile.parentFile?.mkdirs()

                    try {
                        val url = URL(TILE_URL_TEMPLATE.format(z, x, y))
                        val conn = url.openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", "ADOSAndroidGCS/1.0")
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000

                        if (conn.responseCode == 200) {
                            conn.inputStream.use { input ->
                                FileOutputStream(tileFile).use { output ->
                                    val bytes = input.readBytes()
                                    output.write(bytes)
                                    totalBytes += bytes.size
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download tile z=$z x=$x y=$y: ${e.message}")
                    }

                    downloaded++
                    val prog = downloaded.toFloat() / estimate.tileCount
                    _progress.value = prog
                    onProgress(prog)
                }
            }
        }

        // Save pack metadata
        val pack = TilePack(
            id = packId,
            name = "Area ${_downloadedPacks.value.size + 1}",
            bounds = bounds,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileCount = downloaded,
            sizeMb = totalBytes / (1024f * 1024f),
            createdAt = System.currentTimeMillis(),
        )
        _downloadedPacks.value = _downloadedPacks.value + pack

        // Evict oldest packs if total cache exceeds 500MB
        evictOldTiles()

        _downloading.value = false
        Log.i(TAG, "Download complete: $downloaded tiles, ${"%.1f".format(pack.sizeMb)} MB")
    }

    fun cancelDownload() {
        cancelled = true
    }

    fun getDownloadedPacks(): List<TilePack> = _downloadedPacks.value

    fun deletePack(id: String) {
        val packDir = File(tileDir, id)
        if (packDir.exists()) {
            packDir.deleteRecursively()
        }
        _downloadedPacks.value = _downloadedPacks.value.filter { it.id != id }
        Log.i(TAG, "Deleted tile pack: $id")
    }

    fun loadPacks() {
        val packs = mutableListOf<TilePack>()
        tileDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            // Count tiles
            var count = 0
            var size = 0L
            dir.walkTopDown().filter { it.isFile }.forEach {
                count++
                size += it.length()
            }
            if (count > 0) {
                packs.add(
                    TilePack(
                        id = dir.name,
                        name = dir.name.replace("pack_", "Area "),
                        bounds = TileBounds(0.0, 0.0, 0.0, 0.0),
                        minZoom = 0,
                        maxZoom = 0,
                        tileCount = count,
                        sizeMb = size / (1024f * 1024f),
                        createdAt = dir.lastModified(),
                    )
                )
            }
        }
        _downloadedPacks.value = packs
    }

    /**
     * Evict oldest tile packs (by last-modified time) until the total
     * cache directory is under [MAX_CACHE_SIZE_BYTES]. Runs on IO dispatcher.
     */
    private suspend fun evictOldTiles() = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, TILE_DIR)
        if (!cacheDir.exists()) return@withContext

        val packs = cacheDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return@withContext

        var totalSize = packs.sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        while (totalSize > MAX_CACHE_SIZE_BYTES && packs.isNotEmpty()) {
            val oldest = packs.removeFirst()
            val freedSize = oldest.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            oldest.deleteRecursively()
            totalSize -= freedSize
            // Also remove from in-memory pack list
            _downloadedPacks.value = _downloadedPacks.value.filter { it.id != oldest.name }
            Log.d(TAG, "Evicted tile pack: ${oldest.name}, freed ${freedSize / 1024}KB")
        }
    }

    // Slippy map tile math
    private fun lonToTileX(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom).toDouble()).toInt()

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        val n = (1 shl zoom).toDouble()
        return floor((1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }

    private fun tileXRange(minLon: Double, maxLon: Double, zoom: Int): IntRange {
        val x1 = lonToTileX(minLon, zoom)
        val x2 = lonToTileX(maxLon, zoom)
        return minOf(x1, x2)..maxOf(x1, x2)
    }

    private fun tileYRange(minLat: Double, maxLat: Double, zoom: Int): IntRange {
        val y1 = latToTileY(maxLat, zoom) // Note: lat/y are inverted
        val y2 = latToTileY(minLat, zoom)
        return minOf(y1, y2)..maxOf(y1, y2)
    }
}
