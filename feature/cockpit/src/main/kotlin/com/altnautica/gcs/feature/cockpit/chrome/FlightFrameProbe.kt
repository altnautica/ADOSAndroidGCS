package com.altnautica.gcs.feature.cockpit.chrome

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.altnautica.gcs.core.video.DebugProperties
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A debuggable build's account of the flight screen's frames, logged every 10 s under the
 * `FlightFrames` tag, for a bench to read without a profiler:
 * - window frames per second, the share longer than one 60 Hz frame (janky), p50 and p90 of
 *   the frame total, and the mean of each stage (input, animation, layout, draw, sync,
 *   command issue, swap);
 * - each confirm sheet's response time: from the tap to the end of the frame that drew it;
 * - with `adb shell setprop debug.ados.cockpit.probe 1` (read when the screen opens), the ten
 *   composables that recomposed in the most frames.
 *
 * A release build installs nothing, and [FlightFrames] then records nothing.
 */
@Composable
internal fun FlightFrameProbe() {
    val context = LocalContext.current
    val debuggable = remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    if (!debuggable) return
    val window = remember(context) { context.findActivity()?.window } ?: return
    DisposableEffect(window) {
        val thread = HandlerThread("flight-frames").apply { start() }
        val handler = Handler(thread.looper)
        val main = Handler(android.os.Looper.getMainLooper())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> FlightFrames.frame(metrics) }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        FlightFrames.installed = true
        // The property read starts a process: off the main thread. The tracer is installed only when asked.
        handler.post { if (DebugProperties.read(context, PROBE_PROPERTY) == "1") main.post { if (FlightFrames.installed) FlightFrames.traceCompositions(true) } }
        onDispose {
            FlightFrames.installed = false
            FlightFrames.traceCompositions(false)
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }
    }
    LaunchedEffect(window) {
        while (true) {
            FlightFrames.reset()
            delay(WINDOW_MS)
            Log.i(TAG, FlightFrames.summary(WINDOW_MS))
        }
    }
}

/** Records each press's time for [FlightFrames]' response times; observes only, consumes nothing. */
internal fun Modifier.flightFrameTaps(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && FlightFrames.installed) {
                event.changes.firstOrNull()?.let { FlightFrames.tap(it.uptimeMillis) }
            }
        }
    }
}

/** What [FlightFrameProbe] counts; every entry point is a no-op until it is installed. */
@OptIn(InternalComposeTracingApi::class)
internal object FlightFrames {
    @Volatile
    var installed = false

    @Volatile private var tapNs = 0L

    @Volatile private var respondedNs = 0L

    private val lock = Any()
    private val totals = LongArray(MAX_FRAMES)
    private var frames = 0
    private var janky = 0
    private val stageSums = LongArray(STAGES.size)
    private val responses = ArrayList<Long>()

    private val recomposed = ConcurrentHashMap<String, AtomicInteger>()
    private val inFrame = HashSet<String>()
    private var lastFrameNs = 0L

    /** A press at [uptimeMs] (the event's own time). */
    fun tap(uptimeMs: Long) {
        tapNs = uptimeMs * 1_000_000
        respondedNs = 0L
    }

    /** A confirm sheet entered composition: the response to the last tap. */
    fun responded() {
        if (installed && tapNs != 0L && respondedNs == 0L) respondedNs = System.nanoTime()
    }

    fun frame(m: FrameMetrics) {
        val vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        synchronized(lock) {
            if (frames < MAX_FRAMES) totals[frames] = total
            frames++
            if (total > JANK_NS) janky++
            STAGES.forEachIndexed { i, s -> stageSums[i] += m.getMetric(s) }
            val responded = respondedNs
            if (responded != 0L && responded in vsync..vsync + total) {
                responses += vsync + total - tapNs
                tapNs = 0L
                respondedNs = 0L
            }
        }
    }

    fun reset() = synchronized(lock) {
        frames = 0
        janky = 0
        stageSums.fill(0)
        responses.clear()
        recomposed.clear()
    }

    fun summary(windowMs: Long): String = synchronized(lock) {
        val n = minOf(frames, MAX_FRAMES)
        val sorted = totals.copyOf(n).also { it.sort() }
        fun pct(p: Double) = if (n == 0) 0.0 else sorted[((n - 1) * p).toInt()] / 1e6
        fun mean(i: Int) = if (frames == 0) 0.0 else stageSums[i] / 1e6 / frames
        buildString {
            append("frames_per_s=%.1f janky_pct=%.0f p50_ms=%.1f p90_ms=%.1f".format(frames * 1000.0 / windowMs, if (frames == 0) 0.0 else janky * 100.0 / frames, pct(0.5), pct(0.9)))
            append(" stages_ms=")
            append(STAGE_NAMES.indices.joinToString(",") { "${STAGE_NAMES[it]}:%.1f".format(mean(it)) })
            if (responses.isNotEmpty()) append(" confirm_response_ms=").append(responses.joinToString(",") { "%.0f".format(it / 1e6) })
            if (recomposed.isNotEmpty()) {
                append(" recomposed_frames=")
                append(recomposed.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key.substringAfterLast('.')}:${it.value.get()}" })
            }
        }
    }

    private var tracing = false

    /** Counts, per composable, the frames in which it ran, while [on]; off removes only this tracer. */
    fun traceCompositions(on: Boolean) {
        if (on == tracing) return
        tracing = on
        Composer.setTracer(if (on) tracer else off)
    }

    private val off = object : CompositionTracer {
        override fun isTraceInProgress() = false
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
        override fun traceEventEnd() = Unit
    }

    private val tracer = object : CompositionTracer {
        override fun isTraceInProgress() = true

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            // Compositions run on the main thread; a new frame starts a new set.
            val now = System.nanoTime()
            if (now - lastFrameNs > FRAME_GAP_NS) inFrame.clear()
            lastFrameNs = now
            val name = info.substringBefore(" (")
            if (inFrame.add(name)) recomposed.getOrPut(name) { AtomicInteger() }.incrementAndGet()
        }

        override fun traceEventEnd() = Unit
    }

    private const val MAX_FRAMES = 2_048
    private const val JANK_NS = 16_700_000L
    private const val FRAME_GAP_NS = 8_000_000L
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private const val TAG = "FlightFrames"
private const val PROBE_PROPERTY = "debug.ados.cockpit.probe"
private const val WINDOW_MS = 10_000L

/** The frame stages [FlightFrames] averages, and their names in the log. */
private val STAGES = intArrayOf(
    FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
    FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION,
)
private val STAGE_NAMES = arrayOf("input", "anim", "layout", "draw", "sync", "issue", "swap")
