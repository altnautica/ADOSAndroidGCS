package com.altnautica.gcs.data.flightlog

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records raw MAVLink bytes to .tlog format.
 *
 * Tlog format: sequential records of [8-byte timestamp (microseconds since epoch)] + [raw MAVLink bytes].
 * This matches Mission Planner / QGroundControl tlog format for replay and analysis.
 *
 * Call [recordFrame] from MavLinkRepository.processFrame() to pipe raw bytes before parsing.
 * Auto-start on arm, auto-stop on disarm is handled by FlightSessionTracker.
 */
@Singleton
class TlogRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "TlogRecorder"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var outputStream: BufferedOutputStream? = null
    private var currentFile: File? = null
    private val timestampBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    val tlogDir: File
        get() = File(context.filesDir, "tlogs").also { it.mkdirs() }

    /**
     * Start recording to a new tlog file.
     * @return the file path being written to, or null on failure.
     */
    fun start(filename: String? = null): String? {
        if (_isRecording.value) return currentFile?.absolutePath

        return try {
            val file = if (filename != null) {
                File(tlogDir, filename)
            } else {
                generateOutputFile()
            }
            currentFile = file
            outputStream = BufferedOutputStream(FileOutputStream(file))
            _isRecording.value = true
            Log.i(TAG, "Tlog recording started: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tlog recording: ${e.message}", e)
            null
        }
    }

    /**
     * Stop recording and close the file.
     * @return the path of the completed tlog file, or null if not recording.
     */
    fun stop(): String? {
        if (!_isRecording.value) return null

        _isRecording.value = false
        val path = currentFile?.absolutePath

        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tlog file: ${e.message}")
        }

        outputStream = null
        Log.i(TAG, "Tlog recording stopped: $path")
        return path
    }

    /**
     * Record a raw MAVLink frame. Called from the MAVLink receive path.
     * Writes [8-byte timestamp (us)] + [raw bytes] to the tlog file.
     * Runs on IO dispatcher to avoid blocking the receive loop.
     */
    fun recordFrame(bytes: ByteArray) {
        if (!_isRecording.value) return

        scope.launch {
            try {
                val stream = outputStream ?: return@launch
                val timestampUs = System.currentTimeMillis() * 1000L
                timestampBuffer.clear()
                timestampBuffer.putLong(timestampUs)

                synchronized(stream) {
                    stream.write(timestampBuffer.array())
                    stream.write(bytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error writing tlog frame: ${e.message}")
            }
        }
    }

    private fun generateOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(tlogDir, "$timestamp.tlog")
    }
}
