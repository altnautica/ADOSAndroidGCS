package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.model.VideoState
import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Hands a WebRTC session's decoded frames to the renderers on screen.
 *
 * Two watchdogs, because LIVE means a picture is on screen:
 * [decoded] sees every frame the peer decodes and drives re-dial decisions;
 * [shown] sees only frames handed to an attached renderer. Renderers attach
 * only while they hold a drawing surface (see `VideoSurface`), so a session
 * that decodes with nothing on screen is never reported [VideoState.Live].
 */
class FrameFanout(clock: VideoClock) : VideoSink {
    val decoded = FrameWatchdog(clock)
    val shown = FrameWatchdog(clock)
    private val renderers = CopyOnWriteArraySet<VideoSink>()

    override fun onFrame(frame: VideoFrame) {
        decoded.onFrame()
        if (renderers.isEmpty()) return
        for (r in renderers) r.onFrame(frame)
        shown.onFrame()
    }

    /** The session's [VideoOutput]: renderers add themselves while their surface exists. */
    fun output(eglContext: () -> EglBase.Context): VideoOutput.Sinks = object : VideoOutput.Sinks {
        override val eglContext: EglBase.Context get() = eglContext()
        override fun addSink(sink: VideoSink) { renderers += sink }
        override fun removeSink(sink: VideoSink) { renderers -= sink }
    }

    /** Forget frame times before a new dial. */
    fun reset() {
        decoded.reset()
        shown.reset()
    }

    fun clear() = renderers.clear()

    /**
     * State while decoded frames flow: [VideoState.Live] only when they also
     * reach a renderer; decoding with nothing on screen is still connecting.
     */
    fun flowingState(latencyMs: Int?): VideoState =
        if (shown.check() == FrameWatchdog.Verdict.Flowing) VideoState.Live(latencyMs) else VideoState.Connecting
}
