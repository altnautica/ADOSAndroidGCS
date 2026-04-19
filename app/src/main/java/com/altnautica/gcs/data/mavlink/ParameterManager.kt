package com.altnautica.gcs.data.mavlink

import android.util.Log
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavParamType
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.ParamRequestList
import io.dronefleet.mavlink.common.ParamRequestRead
import io.dronefleet.mavlink.common.ParamSet
import io.dronefleet.mavlink.util.EnumValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages FC parameter download, caching, read, and write.
 *
 * ArduPilot auto-saves params to EEPROM on PARAM_SET (per DEC-047),
 * so writeParam() is sufficient for persistence. commitToFlash() is
 * belt-and-suspenders for non-ArduPilot firmware.
 */
@Singleton
class ParameterManager @Inject constructor(
    private val repository: MavLinkRepository
) {

    companion object {
        private const val TAG = "ParameterManager"
        private const val GCS_SYS_ID = 255
        private const val GCS_COMP_ID = 190
        private const val TARGET_SYS_ID = 1
        private const val TARGET_COMP_ID = 1
        private const val DOWNLOAD_TIMEOUT_MS = 30_000L
        private const val SINGLE_PARAM_TIMEOUT_MS = 5_000L
        private const val RETRY_GAP_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ParamEntry(
        val name: String,
        val value: Float,
        val type: Int,
        val index: Int,
        val count: Int
    )

    enum class DownloadStatus {
        IDLE,
        DOWNLOADING,
        COMPLETE,
        PARTIAL,
        FAILED
    }

    // All params cached from FC, keyed by param name
    private val paramCache = ConcurrentHashMap<String, ParamEntry>()

    private val _params = MutableStateFlow<Map<String, ParamEntry>>(emptyMap())
    val params: StateFlow<Map<String, ParamEntry>> = _params.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadStatus = MutableStateFlow(DownloadStatus.IDLE)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    // Expected total param count (from first PARAM_VALUE response)
    private var expectedCount = 0

    // Pending single-param read deferreds
    private val pendingReads = ConcurrentHashMap<String, CompletableDeferred<ParamEntry?>>()

    /**
     * Called by MavLinkParser when PARAM_VALUE is received.
     * Updates cache and resolves any pending single-param reads.
     */
    fun onParamValue(name: String, value: Float, type: Int, count: Int, index: Int) {
        val entry = ParamEntry(
            name = name,
            value = value,
            type = type,
            index = index,
            count = count
        )

        paramCache[name] = entry
        _params.value = HashMap(paramCache)

        // Track download progress
        if (expectedCount == 0 && count > 0) {
            expectedCount = count
        }
        if (expectedCount > 0) {
            _downloadProgress.value = paramCache.size.toFloat() / expectedCount.toFloat()
            if (paramCache.size >= expectedCount && _downloadStatus.value == DownloadStatus.DOWNLOADING) {
                _downloadStatus.value = DownloadStatus.COMPLETE
                Log.i(TAG, "Parameter download complete: ${paramCache.size}/$expectedCount params")
            }
        }

        // Resolve pending single-param reads
        pendingReads.remove(name)?.complete(entry)
    }

    /**
     * Request full parameter list from FC.
     * Sends PARAM_REQUEST_LIST and waits for all PARAM_VALUE responses.
     * Returns true if all params received within timeout.
     */
    suspend fun requestAllParams(): Boolean {
        Log.i(TAG, "Requesting full parameter list")
        paramCache.clear()
        expectedCount = 0
        _downloadProgress.value = 0f
        _downloadStatus.value = DownloadStatus.DOWNLOADING
        _params.value = emptyMap()

        val msg = ParamRequestList.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .build()
        sendMessage(msg)

        // Wait for download to complete or timeout
        val result = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            while (_downloadStatus.value == DownloadStatus.DOWNLOADING) {
                delay(200)
            }
            true
        }

        if (result == null) {
            // Timeout. Check if we got most params and retry missing ones.
            if (expectedCount > 0 && paramCache.size > 0) {
                Log.w(TAG, "Download timed out with ${paramCache.size}/$expectedCount params. Requesting missing.")
                retryMissingParams()
            } else {
                _downloadStatus.value = DownloadStatus.FAILED
                Log.e(TAG, "Parameter download failed: no params received")
                return false
            }
        }

        return _downloadStatus.value == DownloadStatus.COMPLETE
    }

    /**
     * Request missing params by index after an incomplete bulk download.
     */
    private suspend fun retryMissingParams() {
        if (expectedCount <= 0) return

        val receivedIndices = paramCache.values.map { it.index }.toSet()
        val missingIndices = (0 until expectedCount).filter { it !in receivedIndices }

        Log.d(TAG, "Retrying ${missingIndices.size} missing params")

        for (idx in missingIndices) {
            val msg = ParamRequestRead.builder()
                .targetSystem(TARGET_SYS_ID)
                .targetComponent(TARGET_COMP_ID)
                .paramId("")
                .paramIndex(idx)
                .build()
            sendMessage(msg)
            delay(50) // Small gap to avoid flooding
        }

        // Wait a bit more for stragglers
        val retryResult = withTimeoutOrNull(RETRY_GAP_MS) {
            while (paramCache.size < expectedCount) {
                delay(100)
            }
            true
        }

        if (paramCache.size >= expectedCount) {
            _downloadStatus.value = DownloadStatus.COMPLETE
            Log.i(TAG, "Parameter download complete after retry: ${paramCache.size}/$expectedCount")
        } else {
            _downloadStatus.value = DownloadStatus.PARTIAL
            Log.w(TAG, "Partial download: ${paramCache.size}/$expectedCount params (${expectedCount - paramCache.size} missing)")
        }
    }

    /**
     * Read a single parameter by name.
     * Returns the ParamEntry if received within timeout, null otherwise.
     */
    suspend fun readParam(name: String): ParamEntry? {
        // Check cache first
        paramCache[name]?.let { return it }

        val deferred = CompletableDeferred<ParamEntry?>()
        pendingReads[name] = deferred

        val msg = ParamRequestRead.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .paramId(name)
            .paramIndex(-1) // -1 means use paramId string
            .build()
        sendMessage(msg)

        return withTimeoutOrNull(SINGLE_PARAM_TIMEOUT_MS) {
            deferred.await()
        }.also {
            pendingReads.remove(name)
            if (it == null) {
                Log.w(TAG, "Timeout reading param: $name")
            }
        }
    }

    /**
     * Write a parameter value to the FC.
     * ArduPilot auto-saves to EEPROM on PARAM_SET (DEC-047).
     */
    suspend fun writeParam(name: String, value: Float, type: Int) {
        Log.d(TAG, "Writing param $name = $value (type=$type)")

        val mavType = MavParamType.values().find { EnumValue.of(it).value() == type }
            ?: MavParamType.MAV_PARAM_TYPE_REAL32

        val msg = ParamSet.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .paramId(name)
            .paramValue(value)
            .paramType(mavType)
            .build()
        sendMessage(msg)

        // Optimistically update cache
        paramCache[name]?.let { existing ->
            paramCache[name] = existing.copy(value = value)
            _params.value = HashMap(paramCache)
        }
    }

    /**
     * Belt-and-suspenders flash commit via MAV_CMD_PREFLIGHT_STORAGE.
     * Fire-and-forget: ArduPilot doesn't reliably ACK this command (DEC-047).
     */
    suspend fun commitToFlash() {
        Log.d(TAG, "Sending PREFLIGHT_STORAGE (flash commit)")
        val msg = CommandLong.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .command(MavCmd.MAV_CMD_PREFLIGHT_STORAGE)
            .confirmation(0)
            .param1(1f) // 1 = write params to storage
            .param2(0f)
            .param3(0f)
            .param4(0f)
            .param5(0f)
            .param6(0f)
            .param7(0f)
            .build()
        sendMessage(msg)
    }

    /** Get a cached param value by name. */
    fun getCachedParam(name: String): ParamEntry? = paramCache[name]

    /** Get a cached param value as Float, or default. */
    fun getCachedValue(name: String, default: Float = 0f): Float =
        paramCache[name]?.value ?: default

    /** Check if param cache has been populated. */
    fun isLoaded(): Boolean = paramCache.isNotEmpty()

    /** Total cached param count. */
    fun cachedCount(): Int = paramCache.size

    fun shutdown() {
        scope.cancel()
        pendingReads.values.forEach { it.cancel() }
        pendingReads.clear()
    }

    private suspend fun sendMessage(payload: Any) {
        try {
            val outputStream = ByteArrayOutputStream()
            val connection = MavlinkConnection.create(
                ByteArray(0).inputStream(),
                outputStream
            )
            connection.send2(GCS_SYS_ID, GCS_COMP_ID, payload)
            val bytes = outputStream.toByteArray()
            if (bytes.isNotEmpty()) {
                repository.sendBytes(bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send message: ${e.message}")
        }
    }
}
