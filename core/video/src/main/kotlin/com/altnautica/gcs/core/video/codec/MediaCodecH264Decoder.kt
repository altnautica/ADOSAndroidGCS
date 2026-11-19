package com.altnautica.gcs.core.video.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.altnautica.gcs.core.video.rtp.H264AccessUnit

/**
 * Decodes H.264 access units into a [Surface] with the platform decoder.
 *
 * Synchronous mode on the caller's thread: [submit] queues one unit and
 * renders whatever output is ready, so a receive thread drives decode with
 * no extra threads or callbacks. The decoder is the best one the device has
 * ([PlatformDecoders]): hardware first, in low-latency mode where offered.
 * [onFrameRendered] fires once per picture released to the surface;
 * [onOpened] once per decoder opened, with which one it is.
 */
class MediaCodecH264Decoder(
    private val onFrameRendered: () -> Unit = {},
    private val onOpened: (DecoderInfo) -> Unit = {},
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val info = MediaCodec.BufferInfo()

    /** The open decoder, or null before the first key frame. */
    @Volatile
    var decoder: DecoderInfo? = null
        private set

    /** Attaches or replaces the output surface; null releases the codec. */
    @Synchronized
    fun setSurface(target: Surface?) {
        if (target == surface) return
        val c = codec
        if (target != null && c != null) {
            c.setOutputSurface(target)
        } else {
            release()
        }
        surface = target
    }

    /** Queues one access unit; returns false when it was dropped. */
    @Synchronized
    fun submit(unit: H264AccessUnit, presentationUs: Long): Boolean {
        val target = surface ?: return false
        return try {
            val c = codec ?: if (unit.keyFrame || unit.configOnly) open(target) ?: return false else return false
            val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                drain(c)
                return false
            }
            val buffer = c.getInputBuffer(index) ?: return false
            buffer.clear()
            if (buffer.remaining() < unit.data.size) {
                c.queueInputBuffer(index, 0, 0, presentationUs, 0)
                return false
            }
            buffer.put(unit.data)
            val flags = if (unit.configOnly) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            c.queueInputBuffer(index, 0, unit.data.size, presentationUs, flags)
            drain(c)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decoder error, reopening on next key frame", e)
            release()
            false
        }
    }

    @Synchronized
    fun release() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            if (out < 0) return
            val render = info.size > 0
            c.releaseOutputBuffer(out, render)
            if (render) onFrameRendered()
        }
    }

    /** Earliest time another open is tried after every decoder refused (elapsed ms). */
    private var reopenAfterMs = 0L

    /**
     * Opens the best decoder, or null while backing off: a full walk of the
     * device's decoders runs on the receive thread, so after one that opened
     * nothing the next is held off for [REOPEN_BACKOFF_MS], not tried on every
     * key frame.
     */
    private fun open(target: Surface): MediaCodec? {
        val now = SystemClock.elapsedRealtime()
        if (now < reopenAfterMs) return null
        val (c, opened) = try {
            PlatformDecoders.open(MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_W, DEFAULT_H, target)
        } catch (e: IllegalStateException) {
            reopenAfterMs = now + REOPEN_BACKOFF_MS
            Log.w(TAG, "no decoder opened; trying again in ${REOPEN_BACKOFF_MS / 1000} s", e)
            return null
        }
        codec = c
        decoder = opened
        onOpened(opened)
        return c
    }

    companion object {
        private const val TAG = "AdosVideoDecoder"
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val REOPEN_BACKOFF_MS = 2_000L
        private const val DEFAULT_W = 1280
        private const val DEFAULT_H = 720
    }
}
