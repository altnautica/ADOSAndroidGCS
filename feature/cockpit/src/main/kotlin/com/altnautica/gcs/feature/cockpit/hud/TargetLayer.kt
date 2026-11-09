package com.altnautica.gcs.feature.cockpit.hud

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import com.altnautica.gcs.feature.cockpit.autonomy.Box
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.DrawItem
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.TargetKind
import com.altnautica.gcs.feature.cockpit.autonomy.TargetModifier
import com.altnautica.gcs.feature.cockpit.hud.layers.CanvasTargets
import kotlin.math.abs
import kotlin.math.max

/**
 * The drawn targets of one video surface, shared by the canvas that paints them, the
 * designator that hit-tests taps against them and the TalkBack nodes. Boxes stay in
 * frame px; [frameW] and [frameH] give the frame the rendered rect letterboxes.
 *
 * The layer canvas drives it: [step] re-resolves and eases the boxes from the canvas's
 * frame loop into plain fields read only by the draw phase, so moving boxes redraw
 * without recomposing. [spoken] (the TalkBack nodes) is republished only when the set
 * of drawn targets changes, or at most every [SEMANTICS_MS] while boxes move.
 */
@Stable
internal class TargetLayerState(val cameraId: String) : CanvasTargets {
    var frameW by mutableFloatStateOf(0f)
        internal set
    var frameH by mutableFloatStateOf(0f)
        internal set

    /** The targets for the TalkBack nodes. */
    var spoken by mutableStateOf(emptyList<DrawItem>())
        private set

    /** The latest eased targets, for drawing and tap hit-testing; not snapshot state. */
    @Volatile
    var items: List<DrawItem> = emptyList()
        private set

    /** Clock time of the latest [step]. */
    @Volatile
    var nowMs: Long = 0L
        private set

    // Inputs set from composition.
    @Volatile internal var autonomy: CockpitAutonomy? = null
    @Volatile internal var drawer: TargetDrawer? = null
    @Volatile internal var videoLost: Boolean = false
    @Volatile internal var vehicleYawDeg: () -> Float? = { null }
    @Volatile internal var motion: MotionPrefs = MotionPrefs(reducedMotion = false, lowPower = false)

    private val smooth = HashMap<Int, Box>()
    private val seen = HashSet<Int>()
    private var last = Long.MIN_VALUE
    private var publishedAt = Long.MIN_VALUE
    private var publishedKeys = 0L

    /** (left, top, scale) of the letterboxed frame inside a [w]×[h] surface. */
    fun fit(w: Float, h: Float) = HudGeometry.fitRect(frameW, frameH, w, h)

    override fun receivedAtMs(): Long? =
        autonomy?.feed?.batches?.value?.get(cameraId)?.receivedAtMs

    override fun step(nowMs: Long): Boolean {
        val a = autonomy ?: return false
        val m = motion
        val raw = a.resolve(cameraId, nowMs, videoLost, vehicleYawDeg())
        val k = if (m.reducedMotion || last == Long.MIN_VALUE) 1f else HudGeometry.smoothingAlpha((nowMs - last).toFloat(), SMOOTH_TAU_MS)
        last = nowMs
        var animating = false
        seen.clear()
        var keys = raw.size.toLong()
        val eased = ArrayList<DrawItem>(raw.size)
        for (item in raw) {
            val id = item.trackId
            keys = keys * 31 + (id ?: -1) * 7 + item.kind.ordinal
            val rect = item.rect
            if (id == null || rect == null || item.kind !in SMOOTHED) {
                eased += item
                continue
            }
            seen += id
            val next = smooth[id]?.let { HudGeometry.easeBox(it, rect, k) } ?: rect
            smooth[id] = next
            if (gap(next, rect) > CONVERGED_PX) animating = true
            eased += if (next == rect) item else item.copy(rect = next)
            if (!m.reducedMotion && (item.kind == TargetKind.Searching || item.kind == TargetKind.Lost || TargetModifier.Reacquired in item.modifiers)) {
                animating = true
            }
        }
        smooth.keys.retainAll(seen)
        val batch = a.feed.batches.value[cameraId]
        val predicted = a.feed.follow.value?.value?.predicted?.takeIf { it.cameraId == cameraId }
        val fw = batch?.frameWidth ?: predicted?.frameW
        val fh = batch?.frameHeight ?: predicted?.frameH
        if (fw != null && fw != frameW) frameW = fw
        if (fh != null && fh != frameH) frameH = fh
        items = eased
        this.nowMs = nowMs
        if (keys != publishedKeys || nowMs - publishedAt >= SEMANTICS_MS && spoken != eased) {
            publishedKeys = keys
            publishedAt = nowMs
            spoken = eased
        }
        return animating
    }

    override fun DrawScope.draw(alpha: Float) {
        val d = drawer ?: return
        with(d) { draw(this@TargetLayerState, alpha) }
    }

    companion object {
        /** Fastest republish of the TalkBack nodes while boxes move. */
        const val SEMANTICS_MS = 500L
    }
}

/** Android "Remove animations" (animator duration scale 0) and battery saver. */
internal data class MotionPrefs(val reducedMotion: Boolean, val lowPower: Boolean)

@Composable
internal fun rememberMotionPrefs(): MotionPrefs {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val power = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        MotionPrefs(reducedMotion = scale == 0f, lowPower = power)
    }
}

/** Tracked boxes ease toward each new detection with this time constant (snapped under reduced motion). */
private const val SMOOTH_TAU_MS = 120f
private const val CONVERGED_PX = 0.5f

private val SMOOTHED = setOf(TargetKind.Tracked, TargetKind.Locked, TargetKind.Acquiring, TargetKind.Following, TargetKind.Holding)

private fun gap(a: Box, b: Box) = max(max(abs(a.x - b.x), abs(a.y - b.y)), max(abs(a.w - b.w), abs(a.h - b.h)))
