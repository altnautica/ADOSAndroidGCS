package com.altnautica.gcs.data.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.StatFs
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side video recording using MediaCodec encoder + MediaMuxer.
 *
 * Creates an input Surface that can be composited with the video stream.
 * Encodes H.264 and writes to MP4 container in the app's recordings directory.
 * Tracks recording state, duration, and file size as StateFlows.
 */
@Singleton
class VideoRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val FRAME_RATE = 30
        private const val BITRATE = 4_000_000 // 4 Mbps
        private const val I_FRAME_INTERVAL = 2 // seconds
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MIN_FREE_SPACE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _recordingFileSize = MutableStateFlow(0L)
    val recordingFileSize: StateFlow<Long> = _recordingFileSize.asStateFlow()

    private val _lowStorageWarning = MutableStateFlow(false)
    val lowStorageWarning: StateFlow<Boolean> = _lowStorageWarning.asStateFlow()

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var drainJob: Job? = null
    private var durationJob: Job? = null
    private var startTimeMs = 0L
    private var currentFile: File? = null

    val recordingsDir: File
        get() = File(context.filesDir, "recordings").also { it.mkdirs() }

    /**
     * Start recording. Returns the input Surface that should receive video frames.
     * The caller should render/copy video frames onto this surface.
     */
    fun startRecording(outputFile: File? = null): Surface? {
        if (_isRecording.value) return inputSurface

        try {
            val file = outputFile ?: generateOutputFile()
            currentFile = file

            // Configure encoder
            val format = MediaFormat.createVideoFormat(MIME_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            val enc = MediaCodec.createEncoderByType(MIME_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            encoder = enc

            // Setup muxer
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            muxerStarted = false

            startTimeMs = System.currentTimeMillis()
            _isRecording.value = true
            _recordingDuration.value = 0L
            _recordingFileSize.value = 0L

            // Start drain loop
            drainJob = scope.launch(Dispatchers.IO) { drainEncoder() }

            // Start duration tracking
            durationJob = scope.launch {
                while (isActive && _isRecording.value) {
                    _recordingDuration.value = System.currentTimeMillis() - startTimeMs
                    _recordingFileSize.value = currentFile?.length() ?: 0L
                    kotlinx.coroutines.delay(500)
                }
            }

            Log.i(TAG, "Recording started: ${file.absolutePath}")
            return inputSurface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            releaseRecording()
            return null
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return

        _isRecording.value = false
        drainJob?.cancel()
        durationJob?.cancel()

        try {
            // Signal end of stream
            encoder?.signalEndOfInputStream()

            // Drain remaining buffers
            drainEncoderSync()

            if (muxerStarted) {
                muxer?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording: ${e.message}")
        }

        releaseRecording()
        Log.i(TAG, "Recording stopped: ${currentFile?.absolutePath}")
    }

    fun getRecordedFiles(): List<File> =
        recordingsDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun deleteRecording(file: File): Boolean {
        val deleted = file.delete()
        if (deleted) Log.i(TAG, "Deleted recording: ${file.name}")
        return deleted
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()

        while (_isRecording.value) {
            val idx = enc.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            when {
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec config data, not a media sample
                        enc.releaseOutputBuffer(idx, false)
                        continue
                    }

                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer?.addTrack(enc.outputFormat) ?: -1
                        muxer?.start()
                        muxerStarted = true
                        Log.i(TAG, "Muxer started, format: ${enc.outputFormat}")
                    }
                }
            }
        }
    }

    private fun drainEncoderSync() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()

        // Drain any remaining buffers
        for (i in 0..100) {
            val idx = enc.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0 && muxerStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer?.writeSampleData(trackIndex, buf, info)
                }
                enc.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else {
                break
            }
        }
    }

    private fun releaseRecording() {
        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null

        try {
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null

        inputSurface?.release()
        inputSurface = null
        trackIndex = -1
        muxerStarted = false
    }

    private fun generateOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir, "ADOS_${timestamp}.mp4")
    }
}
