package com.altnautica.gcs.core.cloud.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.altnautica.gcs.core.video.codec.MediaCodecH264Decoder
import com.altnautica.gcs.core.video.rtp.H264AccessUnit

/** Decodes relay samples into a surface. Kept behind an interface so the session is testable without MediaCodec. */
interface RelaySampleDecoder {
    fun setSurface(surface: Surface?)
    fun configure(codec: VideoCodec)
    fun submit(sample: Fmp4Sample): Boolean
    fun release()
}

/**
 * H.264 goes through the shared platform decoder; H.265 through a decoder of
 * the same shape for `video/hevc`. [onFrame] fires per rendered picture.
 */
class MediaCodecRelayDecoder(private val onFrame: () -> Unit) : RelaySampleDecoder {
    private val avc = MediaCodecH264Decoder(onFrame)
    private val hevc = HevcSurfaceDecoder(onFrame)
    @Volatile private var codec: VideoCodec = VideoCodec.Avc
    @Volatile private var surface: Surface? = null

    override fun setSurface(surface: Surface?) {
        this.surface = surface
        when (codec) {
            VideoCodec.Avc -> avc.setSurface(surface)
            VideoCodec.Hevc -> hevc.setSurface(surface)
        }
    }

    override fun configure(codec: VideoCodec) {
        if (codec == this.codec) return
        release()
        this.codec = codec
        setSurface(surface)
    }

    override fun submit(sample: Fmp4Sample): Boolean = when (codec) {
        VideoCodec.Avc -> avc.submit(H264AccessUnit(sample.data, sample.presentationUs, sample.keyFrame, false), sample.presentationUs)
        VideoCodec.Hevc -> hevc.submit(sample)
    }

    override fun release() {
        avc.release()
        hevc.release()
    }
}

/** Synchronous `video/hevc` decoder into one surface, low latency when the codec offers it. */
private class HevcSurfaceDecoder(private val onFrame: () -> Unit) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val info = MediaCodec.BufferInfo()

    @Synchronized
    fun setSurface(target: Surface?) {
        if (target == surface) return
        val c = codec
        if (target != null && c != null) c.setOutputSurface(target) else release()
        surface = target
    }

    @Synchronized
    fun submit(sample: Fmp4Sample): Boolean {
        val target = surface ?: return false
        return try {
            val c = codec ?: if (sample.keyFrame) open(target) else return false
            val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                drain(c)
                return false
            }
            val buffer = c.getInputBuffer(index) ?: return false
            buffer.clear()
            if (buffer.remaining() < sample.data.size) {
                c.queueInputBuffer(index, 0, 0, sample.presentationUs, 0)
                return false
            }
            buffer.put(sample.data)
            c.queueInputBuffer(index, 0, sample.data.size, sample.presentationUs, 0)
            drain(c)
            true
        } catch (e: Exception) {
            // Codec creation, configuration or a codec error: drop this decoder and try
            // again on the next key frame rather than failing the caller.
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
            if (render) onFrame()
        }
    }

    private fun open(target: Surface): MediaCodec {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val format = MediaFormat.createVideoFormat(mime, 1280, 720)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        try {
            val low = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                c.codecInfo.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            if (low) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            c.configure(format, target, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "decoder ${c.name} lowLatency=$low")
            return c
        } catch (e: Exception) {
            runCatching { c.release() }
            throw e
        }
    }

    private companion object {
        const val TAG = "AdosRelayDecoder"
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
