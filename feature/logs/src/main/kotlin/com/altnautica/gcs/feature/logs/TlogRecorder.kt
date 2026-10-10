package com.altnautica.gcs.feature.logs

import android.content.Context
import android.util.Log
import com.altnautica.gcs.core.mavlink.MavCrc
import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.messages.MavCrcExtra
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records raw MAVLink frames to `.tlog` files: sequential records of an 8-byte
 * big-endian timestamp (microseconds since the epoch) followed by one MAVLink
 * frame, the common ground-station layout read by [com.altnautica.gcs.feature.logs.tlog.TlogReader].
 *
 * [FlightSessionTracker] starts one [TlogRecording] per armed period of each
 * vehicle and feeds it every frame from that vehicle's link. Files land in the
 * app-specific `filesDir/tlogs/` directory.
 */
@Singleton
class TlogRecorder internal constructor(
    private val dir: () -> File,
    writeDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this({ File(context.filesDir, "tlogs") }, Dispatchers.IO.limitedParallelism(1))

    // One writer at a time: every file is written and closed on this dispatcher, in arrival order.
    private val scope = CoroutineScope(SupervisorJob() + writeDispatcher)

    val tlogDir: File
        get() = dir().also { it.mkdirs() }

    /**
     * Opens a new tlog file named after the current time and [label] (a vehicle id).
     * @return the open recording, or null when the file cannot be created.
     */
    fun start(label: String? = null): TlogRecording? {
        val file = newFile(label)
        return try {
            TlogRecording(file, BufferedOutputStream(FileOutputStream(file)), scope).also {
                Log.i(TAG, "Tlog recording started: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tlog recording: ${e.message}", e)
            null
        }
    }

    private fun newFile(label: String?): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val suffix = label?.replace(UNSAFE_NAME_CHARS, "_")?.takeIf { it.isNotEmpty() }?.let { "_$it" }.orEmpty()
        val base = "$stamp$suffix"
        val dir = tlogDir
        var file = File(dir, "$base.tlog")
        var n = 1
        while (file.exists()) file = File(dir, "$base-${n++}.tlog")
        return file
    }

    private companion object {
        const val TAG = "TlogRecorder"
        val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

/**
 * One open tlog file. [record] never blocks the receive path: records queue to
 * the recorder's writer, and [close] drains that queue and closes the file on
 * the same writer, so no queued frame is lost or written into another file.
 */
class TlogRecording internal constructor(
    val file: File,
    stream: OutputStream,
    scope: CoroutineScope,
) {
    private val records = Channel<ByteArray>(QUEUE_CAPACITY)
    private val droppedFrames = AtomicLong()

    /** Frames dropped because the writer fell [QUEUE_CAPACITY] records behind. */
    val dropped: Long get() = droppedFrames.get()

    private val writer = scope.launch {
        try {
            for (record in records) stream.write(record)
        } catch (e: Exception) {
            Log.w(TAG, "Error writing tlog ${file.name}: ${e.message}")
        } finally {
            try {
                stream.flush()
                stream.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing tlog ${file.name}: ${e.message}")
            }
        }
    }

    /** Queues [frame] with its receive time (wall clock, microseconds). */
    fun record(frame: MavlinkFrame, timeUs: Long = System.currentTimeMillis() * 1000L) {
        if (records.trySend(TlogEncoding.record(timeUs, frame)).isFailure) droppedFrames.incrementAndGet()
    }

    /** Stops accepting frames, writes every queued one, closes the file and returns its path. */
    suspend fun close(): String {
        records.close()
        writer.join()
        Log.i(TAG, "Tlog recording stopped: ${file.absolutePath} (dropped ${droppedFrames.get()})")
        return file.absolutePath
    }

    private companion object {
        const val TAG = "TlogRecorder"
        const val QUEUE_CAPACITY = 4096
    }
}

/** Serialises a received [MavlinkFrame] back to its wire bytes, prefixed with the tlog timestamp. */
internal object TlogEncoding {
    private const val STX_V1 = 0xFE
    private const val STX_V2 = 0xFD
    private const val HEADER_V1 = 6
    private const val HEADER_V2 = 10
    private const val TIMESTAMP = 8

    fun record(timeUs: Long, frame: MavlinkFrame): ByteArray {
        val len = frame.payload.size
        val v2 = frame.version == 2
        val header = if (v2) HEADER_V2 else HEADER_V1
        val signature = if (v2) frame.signature else null
        val out = ByteArray(TIMESTAMP + header + len + 2 + (signature?.size ?: 0))
        for (i in 0 until TIMESTAMP) out[i] = (timeUs ushr (56 - 8 * i)).toByte()
        var p = TIMESTAMP
        val start = p
        if (v2) {
            out[p++] = STX_V2.toByte()
            out[p++] = len.toByte()
            out[p++] = frame.incompatFlags.toByte()
            out[p++] = frame.compatFlags.toByte()
            out[p++] = frame.seq.toByte()
            out[p++] = frame.sysId.toByte()
            out[p++] = frame.compId.toByte()
            out[p++] = frame.msgId.toByte()
            out[p++] = (frame.msgId ushr 8).toByte()
            out[p++] = (frame.msgId ushr 16).toByte()
        } else {
            out[p++] = STX_V1.toByte()
            out[p++] = len.toByte()
            out[p++] = frame.seq.toByte()
            out[p++] = frame.sysId.toByte()
            out[p++] = frame.compId.toByte()
            out[p++] = frame.msgId.toByte()
        }
        System.arraycopy(frame.payload, 0, out, p, len)
        p += len
        // The checksum covers everything after the start marker, then the message's CRC_EXTRA.
        var crc = MavCrc.accumulate(MavCrc.SEED, out, start + 1, p - start - 1)
        crc = MavCrc.accumulate(crc, MavCrcExtra.crcExtra(frame.msgId) and 0xFF)
        out[p++] = crc.toByte()
        out[p++] = (crc ushr 8).toByte()
        signature?.copyInto(out, p)
        return out
    }
}
