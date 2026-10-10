package com.altnautica.gcs.core.video.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.altnautica.gcs.core.video.RecordingState
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.rtp.H264AccessUnit
import com.altnautica.gcs.core.video.rtp.H264RtpDepacketizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local MP4 recording for paths where the phone itself receives H.264
 * (phone radio, cloud relay). Feed every access unit to [offer]; while
 * recording, the file starts at the next IDR once SPS/PPS are known.
 * Files land in [directory] (app-specific storage) and are listed by Logs.
 */
class H264Dvr(
    private val directory: File,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : VideoRecording {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var armed = false
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var firstTs = -1L
    private var file: File? = null

    private val lock = Any()

    override suspend fun start(): Result<Unit> = synchronized(lock) {
        if (armed || muxer != null) return Result.success(Unit)
        if (!directory.exists() && !directory.mkdirs()) {
            _state.value = RecordingState.Failed("Storage unavailable")
            return Result.failure(IllegalStateException("Cannot create ${directory.name}"))
        }
        armed = true
        val now = wallClock()
        val f = File(directory, "dvr-${STAMP.format(Date(now))}.mp4")
        file = f
        _state.value = RecordingState.Recording(now, f.name)
        return Result.success(Unit)
    }

    override suspend fun stop(): Result<Unit> = synchronized(lock) {
        armed = false
        val m = muxer
        muxer = null
        val result = runCatching {
            if (m != null) {
                m.stop()
                m.release()
            }
        }
        if (m == null) file?.delete()
        file = null
        track = -1
        firstTs = -1
        _state.value = RecordingState.Idle
        return result
    }

    fun offer(unit: H264AccessUnit): Unit = synchronized(lock) {
        for (nal in H264RtpDepacketizer.splitAnnexB(unit.data)) {
            when (nal[0].toInt() and 0x1F) {
                H264RtpDepacketizer.NAL_SPS -> sps = nal
                H264RtpDepacketizer.NAL_PPS -> pps = nal
            }
        }
        if (!armed || unit.configOnly) return
        var m = muxer
        if (m == null) {
            val s = sps ?: return
            val p = pps ?: return
            if (!unit.keyFrame) return
            m = try {
                openMuxer(s, p)
            } catch (e: Exception) {
                armed = false
                _state.value = RecordingState.Failed(e.message ?: "Recording failed")
                return
            }
        }
        if (firstTs < 0) firstTs = unit.rtpTimestamp
        val ptsUs = ((unit.rtpTimestamp - firstTs) and 0xFFFFFFFFL) * 1_000_000L / RTP_CLOCK
        val info = MediaCodec.BufferInfo().apply {
            set(0, unit.data.size, ptsUs, if (unit.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        m.writeSampleData(track, ByteBuffer.wrap(unit.data), info)
    }

    private fun openMuxer(s: ByteArray, p: ByteArray): MediaMuxer {
        val f = requireNotNull(file)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(H264RtpDepacketizer.START_CODE + s))
            setByteBuffer("csd-1", ByteBuffer.wrap(H264RtpDepacketizer.START_CODE + p))
        }
        val m = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        track = m.addTrack(format)
        m.start()
        muxer = m
        return m
    }

    private companion object {
        const val RTP_CLOCK = 90_000L
        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
