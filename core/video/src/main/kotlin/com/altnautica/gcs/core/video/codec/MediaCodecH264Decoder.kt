package com.altnautica.gcs.core.video.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.altnautica.gcs.core.video.rtp.H264AccessUnit

/**
 * Decodes H.264 access units into a [Surface] with the platform decoder.
 *
 * Synchronous mode on the caller's thread: [submit] queues one unit and
 * renders whatever output is ready, so a receive thread drives decode with
 * no extra threads or callbacks. Low-latency decoding is requested when the
 * chosen codec reports [MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency].
 * [onFrameRendered] fires once per picture released to the surface.
 */
class MediaCodecH264Decoder(
    private val onFrameRendered: () -> Unit = {},
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val info = MediaCodec.BufferInfo()

    /** Name of the active decoder and whether low latency was granted, for logs and stats. */
    var codecName: String? = null
        private set
    var lowLatency: Boolean = false
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
        val c = codec ?: if (unit.keyFrame || unit.configOnly) open(target) else return false
        return try {
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

    private fun open(target: Surface): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_W, DEFAULT_H)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val low = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        if (low) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        c.configure(format, target, null, 0)
        c.start()
        codec = c
        codecName = c.name
        lowLatency = low
        Log.i(TAG, "decoder ${c.name} lowLatency=$low")
        return c
    }

    companion object {
        private const val TAG = "AdosVideoDecoder"
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val DEFAULT_W = 1280
        private const val DEFAULT_H = 720
    }
}
