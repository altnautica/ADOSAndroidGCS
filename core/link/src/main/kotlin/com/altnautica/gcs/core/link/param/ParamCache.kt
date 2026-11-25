package com.altnautica.gcs.core.link.param

import android.content.Context
import com.altnautica.gcs.core.link.di.LinkScope
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One flight controller's parameter list: the node, the autopilot component and its firmware identity. */
data class ParamCacheKey(
    val node: DeviceId,
    val sysId: Int,
    val compId: Int,
    val autopilot: Int,
    val vehicleType: Int,
) {
    internal val fileName: String
        get() = "${node.value}_${sysId}_${compId}_${autopilot}_$vehicleType.json".replace(UNSAFE, "_")

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9_.-]")

        fun of(node: DeviceId, target: VehicleTarget) =
            ParamCacheKey(node, target.sysId, target.compId, target.autopilot, target.vehicleType)
    }
}

@Serializable
private data class ParamFile(val v: Int = 1, val count: Int, val savedAtMs: Long, val params: List<Entry>)

@Serializable
private data class Entry(val n: String, val v: Double, val t: Int, val i: Int)

/**
 * Last known parameter list per flight controller, in memory and on disk, so a
 * parameter screen shows values at once while a list download confirms them.
 *
 * Reads go to memory first, then disk. Writes land in memory at once and reach
 * disk 2 s after the last change for that key, through a temporary file and a
 * rename. An unreadable file is deleted and treated as absent.
 */
@Singleton
class ParamCache internal constructor(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @LinkScope scope: CoroutineScope,
    ) : this(File(context.filesDir, DIR_NAME), scope)

    private val memory = ConcurrentHashMap<ParamCacheKey, Pair<Int, Map<String, ParamValue>>>()
    private val pendingWrites = ConcurrentHashMap<ParamCacheKey, Job>()
    private val diskLock = Mutex()

    /** The cached `param_count` and values for [key], or null when none is stored. */
    suspend fun load(key: ParamCacheKey): Pair<Int, Map<String, ParamValue>>? {
        memory[key]?.let { return it }
        val read = withContext(io) { diskLock.withLock { readFile(key) } } ?: return null
        return memory.putIfAbsent(key, read) ?: read
    }

    /** Stores [values] for [key] now; the disk copy follows after [WRITE_DEBOUNCE_MS] without a newer put. */
    fun put(key: ParamCacheKey, count: Int, values: Map<String, ParamValue>) {
        memory[key] = count to values
        pendingWrites.compute(key) { _, previous ->
            previous?.cancel()
            scope.launch {
                delay(WRITE_DEBOUNCE_MS)
                val latest = memory[key] ?: return@launch
                withContext(io) { diskLock.withLock { writeFile(key, latest.first, latest.second) } }
            }
        }
    }

    private fun readFile(key: ParamCacheKey): Pair<Int, Map<String, ParamValue>>? {
        val file = File(dir, key.fileName)
        if (!file.exists()) return null
        return runCatching {
            val parsed = json.decodeFromString(ParamFile.serializer(), file.readText())
            parsed.count to parsed.params.associate { it.n to ParamValue(it.n, it.v, it.t, it.i) }
        }.getOrElse {
            file.delete()
            null
        }
    }

    private fun writeFile(key: ParamCacheKey, count: Int, values: Map<String, ParamValue>) {
        dir.mkdirs()
        val body = ParamFile(
            count = count,
            savedAtMs = System.currentTimeMillis(),
            params = values.values.map { Entry(it.name, it.value, it.type, it.index) },
        )
        val target = File(dir, key.fileName)
        val tmp = File(dir, key.fileName + ".tmp")
        tmp.writeText(json.encodeToString(ParamFile.serializer(), body))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    internal companion object {
        const val DIR_NAME = "param-cache"
        const val WRITE_DEBOUNCE_MS = 2_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
