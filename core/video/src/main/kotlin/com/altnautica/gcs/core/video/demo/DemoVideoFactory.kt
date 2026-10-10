package com.altnautica.gcs.core.video.demo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import androidx.core.graphics.withRotation
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.FrameWatchdog
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoScope
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

/**
 * Video for the simulated demo vehicle: an animated horizon test pattern
 * drawn at 30 fps, so the cockpit's video path, watchdog and layout run
 * exactly as they do with a real feed.
 */
@Singleton
class DemoVideoFactory @Inject constructor(
    @VideoScope private val scope: CoroutineScope,
) : VideoSourceFactory {

    override fun supports(src: VideoSource): Boolean = src.nodeId() == DEMO_NODE

    override fun create(src: VideoSource): VideoSessionHandle = DemoVideoSession(src, scope, VideoClock.Elapsed)

    companion object {
        /** The simulated demo vehicle's id, as published by the direct-link node source. */
        val DEMO_NODE = DeviceId("demo-copter")

        /** The source the selector offers for the demo node. */
        val SOURCE: VideoSource = VideoSource.AgentWhep(DEMO_NODE, "http://127.0.0.1/", null)
    }
}

internal fun VideoSource.nodeId() = when (this) {
    is VideoSource.AgentWhep -> node
    is VideoSource.CloudP2p -> node
    is VideoSource.CloudRelay -> node
    is VideoSource.PhoneRadio -> node
}

private class DemoVideoSession(
    override val source: VideoSource,
    private val scope: CoroutineScope,
    private val clock: VideoClock,
) : VideoSessionHandle {

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    override val state: StateFlow<VideoState> = _state
    override val recording: VideoRecording? = null

    @Volatile private var surface: Surface? = null
    private val watchdog = FrameWatchdog(clock)
    private var job: Job? = null

    override val output: VideoOutput = object : VideoOutput.SurfaceTarget {
        override fun setSurface(surface: Surface?) {
            this@DemoVideoSession.surface = surface
        }

        // A software canvas fills and copies every buffer pixel each frame; at full
        // screen size that cost more than the frame budget, so draw at half size.
        override val bufferScale: Float = DEMO_BUFFER_SCALE
    }

    override fun start() {
        if (job != null) return
        _state.value = VideoState.Connecting
        job = scope.launch(Dispatchers.Default) {
            val startMs = clock.nowMs()
            val pacer = FramePacer(startMs, FRAME_PERIOD_MS)
            while (isActive) {
                val s = surface
                if (s != null && s.isValid) {
                    // A software canvas: a hardware one would queue on the UI's RenderThread
                    // and make every cockpit frame wait behind the test pattern.
                    val drawn = runCatching {
                        val canvas = s.lockCanvas(null)
                        try {
                            drawPattern(canvas, (clock.nowMs() - startMs) / 1000f)
                        } finally {
                            s.unlockCanvasAndPost(canvas)
                        }
                    }.isSuccess
                    if (drawn) watchdog.onFrame()
                }
                _state.value = when (val v = watchdog.check()) {
                    null -> VideoState.Connecting
                    FrameWatchdog.Verdict.Flowing -> VideoState.Live(FRAME_MS.toInt())
                    is FrameWatchdog.Verdict.Frozen -> VideoState.Frozen(v.sinceMs)
                }
                delay(pacer.delayAfterFrame(clock.nowMs()))
            }
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        _state.value = VideoState.Idle
    }

    private val sky = Paint().apply { color = Color.rgb(0x1E, 0x3A, 0x5F) }
    private val ground = Paint().apply { color = Color.rgb(0x4A, 0x37, 0x28) }
    private val line = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f * DEMO_BUFFER_SCALE
        isAntiAlias = true
    }
    private val text = Paint().apply {
        color = Color.WHITE
        textSize = 36f * DEMO_BUFFER_SCALE
        isAntiAlias = true
    }

    private fun drawPattern(canvas: Canvas, tS: Float) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val roll = 15f * sin(2 * PI.toFloat() * tS / 8f)
        val pitchPx = h * 0.08f * sin(2 * PI.toFloat() * tS / 11f)
        canvas.drawPaint(sky)
        canvas.withRotation(roll, w / 2, h / 2) {
            drawRect(-w, h / 2 + pitchPx, 2 * w, 2 * h, ground)
            drawLine(-w, h / 2 + pitchPx, 2 * w, h / 2 + pitchPx, line)
        }
        val inset = 24f * DEMO_BUFFER_SCALE
        canvas.drawText("DEMO", inset, h - inset, text)
    }

    private companion object {
        const val FRAME_MS = 33L
        const val FRAME_PERIOD_MS = 1000.0 / 30
        const val DEMO_BUFFER_SCALE = 0.5f
    }
}
