package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * [LayerStack] counters for the performance check: compositions, and the draw-phase
 * time of every layer draw in a 0.1 ms histogram (0–20 ms). Debuggable builds log a
 * summary every 10 s under the `LayerStack` tag; release builds only count.
 */
object LayerStackDebug {
    val compositions = AtomicLong()
    private val histogram = IntArray(HIST_BINS)
    private var draws = 0L

    fun recordDraw(nanos: Long) {
        val bin = (nanos / 100_000L).toInt().coerceIn(0, HIST_BINS - 1)
        synchronized(histogram) {
            histogram[bin]++
            draws++
        }
    }

    /** Draw-time percentile [p] (0..1) in ms over the window since the last [reset], or null without draws. */
    fun percentileMs(p: Double): Double? = synchronized(histogram) {
        if (draws == 0L) return null
        val target = Math.ceil(draws * p).toLong().coerceAtLeast(1)
        var acc = 0L
        for (i in histogram.indices) {
            acc += histogram[i]
            if (acc >= target) return (i + 1) / 10.0
        }
        HIST_BINS / 10.0
    }

    fun drawCount(): Long = synchronized(histogram) { draws }

    fun reset() = synchronized(histogram) {
        histogram.fill(0)
        draws = 0
    }

    private const val HIST_BINS = 200
}

/**
 * Every data layer of one video in a single canvas, in the spec's z order: depth,
 * surfaces, obstacles, targets ([targets] draws them), the AR route and motion items.
 *
 * New frames, interpolation and staleness only invalidate the draw phase: the frames
 * live in [LayerController.store], read only inside the draw lambda, and a frame-clock
 * loop bumps a draw tick only while items move or a layer's freshness changes. The tree
 * recomposes only when the camera or frame size changes. While the lifecycle is at least
 * STARTED the surface holds the view and demand leases for [cameraId]; below it the loop
 * stops and the leases drop.
 *
 * [frameW]×[frameH] is the video frame the canvas letterboxes (0 takes the AR frame size).
 */
@Composable
fun LayerStack(
    controller: LayerController,
    cameraId: String,
    frameW: Float,
    frameH: Float,
    modifier: Modifier = Modifier,
    targets: (DrawScope.(alpha: Float) -> Unit)? = null,
) {
    SideEffect { LayerStackDebug.compositions.incrementAndGet() }
    val context = LocalContext.current
    LaunchedEffect(controller) {
        controller.useSpec(withContext(Dispatchers.IO) { LayerSpec.load(context.assets) })
    }
    val flags = controller.flags.collectAsStateWithLifecycle()
    val depth = controller.depth.collectAsStateWithLifecycle()
    val measurer = rememberTextMeasurer()
    val painter = remember(measurer) { LayerPainter(measurer) }
    val tick = remember { mutableIntStateOf(0) }
    val drawTargets by rememberUpdatedState(targets)
    val store = controller.store
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, cameraId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val release = controller.attach(cameraId)
            try {
                var bucket = -1
                while (true) {
                    androidx.compose.runtime.withFrameMillis { }
                    val now = controller.now()
                    val animating = store.animating(now)
                    val b = freshnessBucket(store, now)
                    if (animating || b != bucket) {
                        bucket = b
                        tick.intValue++
                    }
                    if (!animating) delay(IDLE_POLL_MS)
                }
            } finally {
                release()
            }
        }
    }
    val debuggable = remember(context) { context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    if (debuggable) {
        LaunchedEffect(Unit) {
            while (true) {
                LayerStackDebug.reset()
                delay(DEBUG_WINDOW_MS)
                android.util.Log.i(
                    "LayerStack",
                    "draws=${LayerStackDebug.drawCount()} p50_ms=${LayerStackDebug.percentileMs(0.5)} " +
                        "p95_ms=${LayerStackDebug.percentileMs(0.95)} max_ms=${LayerStackDebug.percentileMs(1.0)} " +
                        "compositions=${LayerStackDebug.compositions.get()} frames=${store.received}",
                )
            }
        }
    }
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            onDrawBehind {
                tick.intValue // a draw-only read: interpolation and staleness redraw without recomposing
                val t0 = System.nanoTime()
                drawLayers(controller, cameraId, frameW, frameH, flags.value, depth.value, painter, drawTargets)
                LayerStackDebug.recordDraw(System.nanoTime() - t0)
            }
        },
    )
}

/** The draw phase of [LayerStack]: every enabled layer in z order. */
private fun DrawScope.drawLayers(
    controller: LayerController,
    cameraId: String,
    frameW: Float,
    frameH: Float,
    f: LayerFlags,
    depthOptions: DepthOptions,
    painter: LayerPainter,
    drawTargets: (DrawScope.(alpha: Float) -> Unit)?,
) {
    val store = controller.store
    store.version
    val now = controller.now()
    val arCam = store.ar.cur?.cameras?.get(cameraId)
    val fw = if (frameW > 0f) frameW else arCam?.frameW ?: DEFAULT_W
    val fh = if (frameH > 0f) frameH else arCam?.frameH ?: DEFAULT_H
    val (left, top, scale) = HudGeometry.fitRect(fw, fh, size.width, size.height)
    val view = Rect(left, top, left + fw * scale, top + fh * scale)
    var chipY = view.top + 8f * density
    val chipX = view.left + 8f * density
    fun chip(text: String) = with(painter) {
        chip(text, Offset(chipX, chipY))
        chipY += 24f * density
    }
    for (id in controller.spec.canvasOrder) {
        if (!f.isOn(id)) continue
        when (id) {
            LayerSpec.DEPTH -> {
                val fr = freshness(store.depth, now)
                val frame = store.depth.cur
                if (fr == Freshness.Hidden) {
                    chip(UNAVAILABLE_DEPTH)
                    continue
                }
                val grid = frame?.cameras?.get(cameraId) ?: continue
                val d = depthOptions
                val range = d.rangeM ?: continue
                val unscaled = (grid.scaleSource ?: frame.scaleSource) == "none"
                with(painter) { drawGrid(grid, view, range, if (unscaled) CellStyle.Outline else d.style, alpha(fr)) }
                if (unscaled) chip(DEPTH_UNSCALED)
            }
            LayerSpec.SURFACES -> {
                val fr = freshness(store.surfaces, now, SURFACES_HIDE_MS)
                if (fr == Freshness.Hidden) {
                    chip(UNAVAILABLE_SURFACES)
                    continue
                }
                val frame = store.surfaces.cur?.takeIf { it.cameraId == cameraId } ?: continue
                with(painter) { drawSurfaces(frame, view, 1f) }
            }
            LayerSpec.OBSTACLES -> {
                val fr = freshness(store.obstacles, now)
                val frame = store.obstacles.cur ?: continue
                if (fr == Freshness.Hidden) {
                    chip(UNAVAILABLE_OBSTACLES)
                    continue
                }
                frame.cameras[cameraId]?.let { g -> with(painter) { drawGrid(g, view, MID_MAX_M, CellStyle.Solid, alpha(fr)) } }
                with(painter) { drawSectorArc(frame.sectorsM, view, alpha(fr)) }
            }
            LayerSpec.TARGETS -> drawTargets?.invoke(this, 1f)
            LayerSpec.AR_ROUTE -> {
                // Route and motion share the `ar` frame; drawn once at the route's z with both groups.
                val frame = store.ar.cur ?: continue
                val fr = freshness(store.ar, now)
                if (fr == Freshness.Hidden || frame.reason != null || arCam?.reason != null) {
                    chip(UNAVAILABLE_AR)
                    continue
                }
                val route = ArRules.routeStyle(frame.mission, controller.previewAllowed())
                with(painter) { drawAr(store.ar, cameraId, view, f, route, now, alpha(fr)) }
                if (frame.approx) chip(AR_APPROX)
            }
            LayerSpec.AR_MOTION -> if (!f.isOn(LayerSpec.AR_ROUTE)) {
                val frame = store.ar.cur ?: continue
                val fr = freshness(store.ar, now)
                if (fr == Freshness.Hidden || frame.reason != null || arCam?.reason != null) continue
                with(painter) { drawAr(store.ar, cameraId, view, f, null, now, alpha(fr)) }
            }
        }
    }
}

private fun freshness(slot: LayerSlot<*>, now: Long, hideMs: Long = LayerStore.HIDE_MS): Freshness {
    val age = slot.ageMs(now) ?: return Freshness.Hidden
    return when {
        age > hideMs -> Freshness.Hidden
        age > LayerStore.DIM_MS -> Freshness.Dim
        else -> Freshness.Fresh
    }
}

private fun alpha(f: Freshness) = if (f == Freshness.Dim) DIM_ALPHA else 1f

/** One number for every layer's freshness, so the loop redraws exactly when one changes. */
private fun freshnessBucket(store: LayerStore, now: Long): Int =
    freshness(store.ar, now).ordinal +
        3 * freshness(store.obstacles, now).ordinal +
        9 * freshness(store.depth, now).ordinal +
        27 * freshness(store.surfaces, now, SURFACES_HIDE_MS).ordinal

private const val IDLE_POLL_MS = 100L
private const val DEBUG_WINDOW_MS = 10_000L
private const val DIM_ALPHA = 0.5f
private const val MID_MAX_M = 5f
private const val DEFAULT_W = 1280f
private const val DEFAULT_H = 720f

/** Surfaces arrive at 1 Hz, so they hide after three missed frames rather than 2 s. */
private const val SURFACES_HIDE_MS = 3_000L

private const val UNAVAILABLE_AR = "AR unavailable"
private const val UNAVAILABLE_OBSTACLES = "Obstacles unavailable"
private const val UNAVAILABLE_DEPTH = "Depth unavailable"
private const val UNAVAILABLE_SURFACES = "Surfaces unavailable"
private const val DEPTH_UNSCALED = "Depth unscaled"
private const val AR_APPROX = "AR ≈"
