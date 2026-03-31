package com.altnautica.gcs.data.cloud

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud video client for Mode C relay (DEC-070/071, DEC-080).
 *
 * Connects to the ADOS video relay at video.altnautica.com over WebSocket.
 * Receives fragmented MP4 (fMP4) binary frames containing H.264 video.
 * Extracts NAL units from mdat boxes and feeds them to Android's hardware
 * MediaCodec decoder, rendering to a provided Surface.
 *
 * The video relay (tools/video-relay/) converts RTSP from the drone agent
 * to fMP4 over WebSocket using ffmpeg (copy codec, zero transcoding).
 * Expected codec: H.264 Baseline/Main profile (avc1.640029).
 * Typical latency: 0.5-1.5 seconds through the relay chain.
 *
 * Two usage modes:
 *   1. Surface mode: call connect(deviceId, surface) for direct rendering
 *   2. Callback mode: set onVideoData for raw fMP4 segment passthrough
 */
@Singleton
class CloudVideoClient @Inject constructor(
    private val httpClient: HttpClient,
) {

    companion object {
        private const val TAG = "CloudVideoClient"
        private const val VIDEO_RELAY_BASE = "wss://video.altnautica.com"
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val CODEC_TIMEOUT_US = 10_000L // 10ms dequeue timeout
        private const val RECONNECT_DELAY_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _frameCount = MutableStateFlow(0L)
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    /**
     * Raw fMP4 segment callback for consumers that handle their own decode.
     * If a Surface is provided via connect(deviceId, surface), this client
     * handles decode internally and this callback is optional (for recording, etc).
     */
    var onVideoData: ((ByteArray) -> Unit)? = null

    // MediaCodec state
    private var codec: MediaCodec? = null
    @Volatile
    private var codecConfigured = false
    private var outputSurface: Surface? = null
    private var spsNalu: ByteArray? = null
    private var ppsNalu: ByteArray? = null

    /**
     * Connect with direct Surface rendering (preferred for video playback).
     * Decodes H.264 NAL units from fMP4 and renders to the Surface.
     */
    fun connect(deviceId: String, surface: Surface) {
        outputSurface = surface
        connectInternal(deviceId)
    }

    /**
     * Connect in callback-only mode (no built-in decode).
     * Register onVideoData before calling this.
     */
    fun connect(deviceId: String) {
        connectInternal(deviceId)
    }

    private fun connectInternal(deviceId: String) {
        if (sessionJob?.isActive == true) return
        Log.i(TAG, "Connecting to video relay for device $deviceId")

        sessionJob = scope.launch {
            // Reconnect loop
            while (isActive) {
                try {
                    httpClient.webSocket("$VIDEO_RELAY_BASE/ws/$deviceId") {
                        _connected.value = true
                        codecConfigured = false
                        spsNalu = null
                        ppsNalu = null
                        Log.i(TAG, "Video relay WebSocket connected")

                        for (frame in incoming) {
                            if (!isActive) break
                            when (frame) {
                                is Frame.Binary -> {
                                    val data = frame.readBytes()
                                    onVideoData?.invoke(data)
                                    // If we have a Surface, extract and decode H.264
                                    if (outputSurface != null) {
                                        processFmp4Segment(data)
                                    }
                                }
                                is Frame.Text -> {
                                    Log.d(TAG, "Relay control: ${frame.readBytes().decodeToString()}")
                                }
                                else -> { /* ping/pong handled by Ktor */ }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Video relay connection error: ${e.message}")
                }

                _connected.value = false
                releaseCodec()
                if (!isActive) break
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    /**
     * Process an fMP4 segment from the video relay.
     *
     * The relay sends: init segment first (moov box with avcC containing SPS/PPS),
     * then moof+mdat pairs. The mdat payload has length-prefixed H.264 NAL units
     * (4-byte big-endian length prefix, as is standard in MP4/ISO BMFF).
     *
     * We scan for mdat and moov boxes, extract NAL units, convert to Annex B
     * (start code prefix), and feed to MediaCodec.
     */
    private fun processFmp4Segment(data: ByteArray) {
        var offset = 0
        while (offset + 8 <= data.size) {
            val boxSize = readBigEndianInt(data, offset)
            if (boxSize < 8) {
                offset++
                continue
            }
            val boxType = String(data, offset + 4, 4, Charsets.US_ASCII)
            val boxEnd = minOf(offset + boxSize, data.size)

            when (boxType) {
                "moov" -> extractAvcC(data, offset + 8, boxEnd)
                "mdat" -> extractAndDecodeNalUnits(data, offset + 8, boxEnd)
            }

            offset = boxEnd
        }
    }

    /**
     * Search for avcC box inside a moov segment to extract SPS/PPS.
     * avcC is nested: moov > trak > mdia > minf > stbl > stsd > avc1 > avcC
     * We do a simple byte scan for the "avcC" signature.
     */
    private fun extractAvcC(data: ByteArray, start: Int, end: Int) {
        for (i in start until end - 4) {
            if (data[i] == 'a'.code.toByte() &&
                data[i + 1] == 'v'.code.toByte() &&
                data[i + 2] == 'c'.code.toByte() &&
                data[i + 3] == 'C'.code.toByte()
            ) {
                val pos = i + 4
                if (pos + 6 >= end) return
                val version = data[pos].toInt() and 0xFF
                if (version != 1) return

                var cur = pos + 5

                // SPS entries
                val numSps = data[cur].toInt() and 0x1F
                cur++
                for (s in 0 until numSps) {
                    if (cur + 2 > end) return
                    val spsLen = ((data[cur].toInt() and 0xFF) shl 8) or (data[cur + 1].toInt() and 0xFF)
                    cur += 2
                    if (cur + spsLen > end) return
                    synchronized(this@CloudVideoClient) {
                        spsNalu = data.copyOfRange(cur, cur + spsLen)
                    }
                    cur += spsLen
                }

                // PPS entries
                if (cur >= end) return
                val numPps = data[cur].toInt() and 0xFF
                cur++
                for (p in 0 until numPps) {
                    if (cur + 2 > end) return
                    val ppsLen = ((data[cur].toInt() and 0xFF) shl 8) or (data[cur + 1].toInt() and 0xFF)
                    cur += 2
                    if (cur + ppsLen > end) return
                    synchronized(this@CloudVideoClient) {
                        ppsNalu = data.copyOfRange(cur, cur + ppsLen)
                    }
                    cur += ppsLen
                }

                Log.i(TAG, "Extracted SPS (${synchronized(this@CloudVideoClient) { spsNalu }?.size}B) + PPS (${synchronized(this@CloudVideoClient) { ppsNalu }?.size}B) from avcC")
                return
            }
        }
    }

    /**
     * Extract length-prefixed NAL units from an mdat payload, convert to
     * Annex B format, and feed to MediaCodec.
     */
    private fun extractAndDecodeNalUnits(data: ByteArray, start: Int, end: Int) {
        val surface = outputSurface ?: return
        var pos = start

        while (pos + 4 < end) {
            val nalLen = readBigEndianInt(data, pos)
            pos += 4
            if (nalLen <= 0 || pos + nalLen > end) break

            val nalType = data[pos].toInt() and 0x1F

            // Capture SPS (7) and PPS (8) from in-band NAL units too
            if (nalType == 7) synchronized(this) { spsNalu = data.copyOfRange(pos, pos + nalLen) }
            if (nalType == 8) synchronized(this) { ppsNalu = data.copyOfRange(pos, pos + nalLen) }

            // Initialize codec when we have both SPS and PPS
            if (!codecConfigured && synchronized(this) { spsNalu != null && ppsNalu != null }) {
                initCodec(surface)
            }

            if (codecConfigured) {
                // Build Annex B: 4-byte start code + NAL data
                val annexB = ByteArray(4 + nalLen)
                annexB[3] = 0x01
                System.arraycopy(data, pos, annexB, 4, nalLen)
                feedToCodec(annexB)
            }

            pos += nalLen
        }
    }

    /**
     * Initialize the hardware H.264 decoder with SPS/PPS as CSD buffers.
     */
    private fun initCodec(surface: Surface) {
        val sps = spsNalu ?: return
        val pps = ppsNalu ?: return

        try {
            val format = MediaFormat.createVideoFormat(MIME_AVC, DEFAULT_WIDTH, DEFAULT_HEIGHT)

            // CSD-0: start code + SPS
            val csd0 = ByteBuffer.allocate(4 + sps.size)
            csd0.put(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            csd0.put(sps)
            csd0.flip()
            format.setByteBuffer("csd-0", csd0)

            // CSD-1: start code + PPS
            val csd1 = ByteBuffer.allocate(4 + pps.size)
            csd1.put(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            csd1.put(pps)
            csd1.flip()
            format.setByteBuffer("csd-1", csd1)

            // Request low latency decode (Android 11+)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

            val decoder = MediaCodec.createDecoderByType(MIME_AVC)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            codecConfigured = true

            // Start output drain coroutine
            scope.launch(Dispatchers.IO) { drainOutput(decoder) }

            Log.i(TAG, "MediaCodec H.264 decoder started")
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec init failed: ${e.message}", e)
            codecConfigured = false
        }
    }

    /**
     * Queue an Annex B NAL unit into the codec's input buffer.
     */
    private fun feedToCodec(annexBNalu: ByteArray) {
        val decoder = codec ?: return
        try {
            val idx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (idx >= 0) {
                val buf = decoder.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(annexBNalu)
                decoder.queueInputBuffer(idx, 0, annexBNalu.size, System.nanoTime() / 1000, 0)
            }
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "Codec input error: ${e.message}")
        }
    }

    /**
     * Drain decoded output buffers so frames render to the Surface.
     * Runs on IO dispatcher until codec is released.
     */
    private fun drainOutput(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (codecConfigured) {
            try {
                val idx = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    idx >= 0 -> {
                        decoder.releaseOutputBuffer(idx, true) // render = true
                        _frameCount.value++
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "Decoder output format: ${decoder.outputFormat}")
                    }
                    // INFO_TRY_AGAIN_LATER: no frame ready, loop continues
                }
            } catch (e: MediaCodec.CodecException) {
                Log.w(TAG, "Codec output error: ${e.message}")
                break
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    private fun releaseCodec() {
        codecConfigured = false
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        synchronized(this) {
            spsNalu = null
            ppsNalu = null
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        releaseCodec()
        _connected.value = false
        _frameCount.value = 0
        outputSurface = null
        onVideoData = null
    }

    private fun readBigEndianInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}
