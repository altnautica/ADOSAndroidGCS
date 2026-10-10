package com.altnautica.gcs.core.video

import android.view.Surface
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoSink

/**
 * Where a session's decoded pictures go. A WebRTC session hands out frames
 * to sinks drawn with a shared EGL context; a MediaCodec session decodes
 * straight into one [Surface].
 */
sealed interface VideoOutput {
    /**
     * Frames delivered to [VideoSink]s rendered with [eglContext]. A renderer
     * is added only while it holds a drawing surface: the session counts a
     * frame as shown (and reports LIVE) only when some sink received it.
     */
    interface Sinks : VideoOutput {
        val eglContext: EglBase.Context
        fun addSink(sink: VideoSink)
        fun removeSink(sink: VideoSink)
    }

    /** A decoder that renders into one surface at a time; null detaches it. */
    interface SurfaceTarget : VideoOutput {
        fun setSurface(surface: Surface?)
    }
}

/** One running video session for one source. */
interface VideoSessionHandle {
    val source: VideoSource
    val state: StateFlow<VideoState>
    val output: VideoOutput

    /** Recording for this session, or null when the path cannot record. */
    val recording: VideoRecording?

    /** Starts dialling; idempotent. */
    fun start()

    /** Tears the session down, releasing remote resources. Idempotent. */
    suspend fun stop()
}

/**
 * Builds sessions for the sources it understands. Bound with `@IntoSet`;
 * the first factory whose [supports] answers true wins.
 */
interface VideoSourceFactory {
    fun supports(src: VideoSource): Boolean
    fun create(src: VideoSource): VideoSessionHandle
}

/** Recording control for one session. */
interface VideoRecording {
    val state: StateFlow<RecordingState>
    suspend fun start(): Result<Unit>
    suspend fun stop(): Result<Unit>
}

sealed interface RecordingState {
    data object Idle : RecordingState

    /** [startedAtMs] is wall-clock ms so a timer survives remounts. */
    data class Recording(val startedAtMs: Long, val target: String?) : RecordingState
    data class Failed(val reason: String) : RecordingState
}
