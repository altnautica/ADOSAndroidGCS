package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.altnautica.gcs.feature.cockpit.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * [LayerStack] counters for the performance check: compositions, redraw ticks, and the
 * draw-phase time of every layer draw in a 0.1 ms histogram (0–20 ms). Debuggable builds
 * log a summary every 10 s under the `LayerStack` tag; release builds only count.
 */
object LayerStackDebug {
    val compositions = AtomicLong()

    /** Redraws the frame loop requested (moving items, freshness changes). */
    val ticks = AtomicLong()
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
 * The targets layer as the canvas drives it: the video's detection boxes and follow
 * symbology. The canvas's frame loop calls [step] and redraws while it reports motion;
 * the layer dims and hides by the age of the camera's newest detection batch.
 */
interface CanvasTargets {
    /** Local receive time of the camera's newest detection batch, or null before the first. */
    fun receivedAtMs(): Long?

    /** Re-resolves and eases the targets to [nowMs]; true while anything still moves. */
    fun step(nowMs: Long): Boolean

    fun DrawScope.draw(alpha: Float)
}

/**
 * How the window's layout class (`layout.json`) shapes the layers: [fpsCap] caps the
 * redraw rate (the TV class draws at 30 fps), and [distanceOnly] keeps only distances
 * in the labels (no waypoint ETA). [reducedRate] marks layers fed by the 2 Hz status
 * poll because the navigation socket cannot open (a radio link). [chipInset] keeps the
 * layer chips (a hidden layer's "unavailable", "Depth unscaled") off the chrome drawn over
 * the video: they stack upward from the bottom-left of the video inside it.
 */
data class LayerPresentation(
    val fpsCap: Int? = null,
    val distanceOnly: Boolean = false,
    val reducedRate: Boolean = false,
    val chipInset: PaddingValues = PaddingValues(0.dp),
)

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
    targets: CanvasTargets? = null,
    presentation: LayerPresentation = LayerPresentation(),
) {
    SideEffect { LayerStackDebug.compositions.incrementAndGet() }
    val context = LocalContext.current
    LaunchedEffect(controller) {
        controller.useSpec(withContext(Dispatchers.IO) { LayerSpec.load(context.assets) })
    }
    val flags = controller.flags.collectAsStateWithLifecycle()
    val depth = controller.depth.collectAsStateWithLifecycle()
    val measurer = rememberTextMeasurer()
    val labels = rememberLayerLabels()
    val canvas = remember(measurer, labels) { LayerCanvas(LayerPainter(measurer), labels) }
    val tick = remember { mutableIntStateOf(0) }
    val currentTargets by rememberUpdatedState(targets)
    val currentPresentation by rememberUpdatedState(presentation)
    val store = controller.store
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, cameraId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val release = controller.attach(cameraId)
            try {
                var bucket = -1
                var lastTickFrame = Long.MIN_VALUE
                while (true) {
                    val frameMs = withFrameMillis { it }
                    // The TV class caps the redraw rate; frames inside the cap are skipped whole.
                    val minGap = currentPresentation.fpsCap?.takeIf { it > 0 }?.let { 1000L / it - FRAME_SLACK_MS } ?: 0L
                    if (minGap > 0 && lastTickFrame != Long.MIN_VALUE && frameMs - lastTickFrame < minGap) continue
                    val now = controller.now()
                    val t = currentTargets
                    val targetsMoving = t?.step(now) == true
                    val animating = store.animating(now) || targetsMoving
                    val b = freshnessBucket(store, t, now)
                    if (animating || b != bucket) {
                        bucket = b
                        lastTickFrame = frameMs
                        tick.intValue++
                        LayerStackDebug.ticks.incrementAndGet()
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
                with(canvas) { draw(controller, cameraId, frameW, frameH, flags.value, depth.value, currentPresentation, currentTargets) }
                LayerStackDebug.recordDraw(System.nanoTime() - t0)
            }
        },
    )
}

/**
 * The draw phase of [LayerStack]: every enabled layer in z order. It keeps the
 * letterboxed view rect and the chip cursor across draws, so a steady draw allocates
 * nothing.
 */
internal class LayerCanvas(private val painter: LayerPainter, private val labels: LayerLabels) {
    private var keyFw = -1f
    private var keyFh = -1f
    private var keyW = -1f
    private var keyH = -1f
    private var view = Rect.Zero
    private var chipX = 0f
    private var chipY = 0f

    /** The letterboxed frame inside the canvas; rebuilt only when a size changes. */
    private fun layout(fw: Float, fh: Float, w: Float, h: Float) {
        if (fw == keyFw && fh == keyFh && w == keyW && h == keyH) return
        keyFw = fw
        keyFh = fh
        keyW = w
        keyH = h
        view = if (fw <= 0f || fh <= 0f) {
            Rect(0f, 0f, w, h)
        } else {
            val scale = min(w / fw, h / fh)
            val left = (w - fw * scale) / 2
            val top = (h - fh * scale) / 2
            Rect(left, top, left + fw * scale, top + fh * scale)
        }
    }

    private fun DrawScope.chip(text: String) {
        with(painter) { chip(text, Offset(chipX, chipY)) }
        chipY -= CHIP_STEP_DP * density
    }

    fun DrawScope.draw(
        controller: LayerController,
        cameraId: String,
        frameW: Float,
        frameH: Float,
        f: LayerFlags,
        depthOptions: DepthOptions,
        presentation: LayerPresentation,
        targets: CanvasTargets?,
    ) {
        val store = controller.store
        store.version
        val now = controller.now()
        val arCam = store.ar.cur?.cameras?.get(cameraId)
        val fw = if (frameW > 0f) frameW else arCam?.frameW ?: DEFAULT_W
        val fh = if (frameH > 0f) frameH else arCam?.frameH ?: DEFAULT_H
        layout(fw, fh, size.width, size.height)
        val view = view
        val inset = presentation.chipInset
        chipX = max(view.left, inset.calculateLeftPadding(layoutDirection).toPx()) + CHIP_INSET_DP * density
        chipY = min(view.bottom, size.height - inset.calculateBottomPadding().toPx()) - (CHIP_INSET_DP + CHIP_STEP_DP) * density
        painter.etaInLabels = !presentation.distanceOnly
        if (presentation.reducedRate) chip(labels.reducedRate)
        val order = controller.spec.canvasOrder
        for (i in order.indices) {
            val id = order[i]
            if (!f.isOn(id)) continue
            when (id) {
                LayerSpec.DEPTH -> {
                    val fr = freshness(store.depth, now)
                    val frame = store.depth.cur
                    if (fr == Freshness.Hidden) {
                        chip(labels.depthUnavailable)
                        continue
                    }
                    val grid = frame?.cameras?.get(cameraId) ?: continue
                    val range = depthOptions.rangeM ?: continue
                    val unscaled = (grid.scaleSource ?: frame.scaleSource) == "none"
                    with(painter) { drawGrid(grid, view, range, if (unscaled) CellStyle.Outline else depthOptions.style, alpha(fr)) }
                    if (unscaled) chip(labels.depthUnscaled)
                }
                LayerSpec.SURFACES -> {
                    val fr = freshness(store.surfaces, now, SURFACES_HIDE_MS)
                    if (fr == Freshness.Hidden) {
                        chip(labels.surfacesUnavailable)
                        continue
                    }
                    val frame = store.surfaces.cur?.takeIf { it.cameraId == cameraId } ?: continue
                    with(painter) { drawSurfaces(frame, view, 1f) }
                }
                LayerSpec.OBSTACLES -> {
                    val fr = freshness(store.obstacles, now)
                    val frame = store.obstacles.cur ?: continue
                    if (fr == Freshness.Hidden) {
                        chip(labels.obstaclesUnavailable)
                        continue
                    }
                    frame.cameras[cameraId]?.let { g -> with(painter) { drawGrid(g, view, MID_MAX_M, CellStyle.Measured, alpha(fr)) } }
                    with(painter) { drawSectorArc(frame.sectorsM, view, alpha(fr)) }
                }
                LayerSpec.TARGETS -> {
                    val t = targets ?: continue
                    val fr = targetsFreshness(t, now)
                    if (fr == Freshness.Hidden) {
                        chip(labels.targetsUnavailable)
                        continue
                    }
                    with(t) { draw(alpha(fr)) }
                }
                LayerSpec.AR_ROUTE -> {
                    // Route and motion share the `ar` frame; drawn once at the route's z with both groups.
                    val frame = store.ar.cur ?: continue
                    val fr = freshness(store.ar, now)
                    if (fr == Freshness.Hidden || frame.reason != null || arCam?.reason != null) {
                        chip(labels.arUnavailable)
                        continue
                    }
                    val route = ArRules.routeStyle(frame.mission, controller.previewAllowed())
                    with(painter) { drawAr(store.ar, cameraId, view, f, route, now, alpha(fr)) }
                    if (frame.approx) chip(labels.arApprox)
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
}

private fun freshness(slot: LayerSlot<*>, now: Long, hideMs: Long = LayerStore.HIDE_MS): Freshness {
    val age = slot.ageMs(now) ?: return Freshness.Hidden
    return when {
        age > hideMs -> Freshness.Hidden
        age > LayerStore.DIM_MS -> Freshness.Dim
        else -> Freshness.Fresh
    }
}

/**
 * The targets layer's freshness by its newest detection batch: dim after 500 ms, hidden
 * (with the unavailable chip) after 2 s. Before the first batch nothing is stale: the
 * layer draws whatever follow symbology it has.
 */
internal fun targetsFreshness(targets: CanvasTargets, now: Long): Freshness {
    val at = targets.receivedAtMs() ?: return Freshness.Fresh
    return LayerStore.freshness((now - at).coerceAtLeast(0))
}

private fun alpha(f: Freshness) = if (f == Freshness.Dim) DIM_ALPHA else 1f

/** One number for every layer's freshness, so the loop redraws exactly when one changes. */
private fun freshnessBucket(store: LayerStore, targets: CanvasTargets?, now: Long): Int =
    freshness(store.ar, now).ordinal +
        3 * freshness(store.obstacles, now).ordinal +
        9 * freshness(store.depth, now).ordinal +
        27 * freshness(store.surfaces, now, SURFACES_HIDE_MS).ordinal +
        81 * (targets?.let { targetsFreshness(it, now).ordinal } ?: 0)

private const val IDLE_POLL_MS = 100L
private const val DEBUG_WINDOW_MS = 10_000L
private const val DIM_ALPHA = 0.5f
private const val MID_MAX_M = 5f
private const val DEFAULT_W = 1280f
private const val DEFAULT_H = 720f
private const val CHIP_INSET_DP = 8f
private const val CHIP_STEP_DP = 24f

/** A display frame lands a little before or after its nominal slot; the cap allows for that. */
private const val FRAME_SLACK_MS = 3L

/** Surfaces arrive at 1 Hz, so they hide after three missed frames rather than 2 s. */
private const val SURFACES_HIDE_MS = 3_000L

/** The canvas chip texts, resolved once per composition. */
internal class LayerLabels(
    val arUnavailable: String,
    val obstaclesUnavailable: String,
    val depthUnavailable: String,
    val surfacesUnavailable: String,
    val targetsUnavailable: String,
    val depthUnscaled: String,
    val arApprox: String,
    val reducedRate: String,
)

@Composable
private fun rememberLayerLabels(): LayerLabels {
    val r = LocalContext.current.resources
    return remember(r) {
        LayerLabels(
            r.getString(R.string.layer_unavailable, r.getString(R.string.layer_ar)),
            r.getString(R.string.layer_unavailable, r.getString(R.string.layer_obstacles)),
            r.getString(R.string.layer_unavailable, r.getString(R.string.layer_depth)),
            r.getString(R.string.layer_unavailable, r.getString(R.string.layer_surfaces)),
            r.getString(R.string.layer_unavailable, r.getString(R.string.layer_targets)),
            r.getString(R.string.layer_depth_unscaled),
            r.getString(R.string.layer_ar_approx),
            r.getString(R.string.layer_reduced_rate),
        )
    }
}
