package com.altnautica.gcs.feature.cockpit.hud

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.altnautica.gcs.feature.cockpit.autonomy.Box
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.DrawItem
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.TargetKind
import com.altnautica.gcs.feature.cockpit.autonomy.TargetModifier
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/**
 * The drawn targets of one video surface, shared by the overlay that paints them and
 * the designator that hit-tests taps against them. Boxes stay in frame px; [frameW] and
 * [frameH] give the frame the rendered rect letterboxes.
 */
@Stable
internal class TargetLayerState(val cameraId: String) {
    var items by mutableStateOf(emptyList<DrawItem>())
        internal set
    var frameW by mutableFloatStateOf(0f)
        internal set
    var frameH by mutableFloatStateOf(0f)
        internal set
    var nowMs by mutableLongStateOf(0L)
        internal set

    /** (left, top, scale) of the letterboxed frame inside a [w]×[h] surface. */
    fun fit(w: Float, h: Float) = HudGeometry.fitRect(frameW, frameH, w, h)
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

private const val SMOOTH_TAU_MS = 120f
private const val CONVERGED_PX = 0.5f
private const val ANIMATING_FRAME_MS = 16L
private const val IDLE_FRAME_MS = 100L

/**
 * Re-resolves the targets for [state]'s camera: at frame rate while boxes ease, a sweep
 * runs or a fade plays, and at detection rate otherwise. Tracked boxes ease toward each
 * new detection with a 120 ms time constant (snapped under reduced motion).
 */
@Composable
internal fun TargetLayerDriver(
    state: TargetLayerState,
    autonomy: CockpitAutonomy,
    videoLost: Boolean,
    vehicleYawDeg: () -> Float?,
    motion: MotionPrefs,
) {
    LaunchedEffect(state, autonomy, videoLost, motion) {
        val smooth = HashMap<Int, Box>()
        var last = autonomy.nowMs()
        while (true) {
            val now = autonomy.nowMs()
            val raw = autonomy.resolve(state.cameraId, now, videoLost, vehicleYawDeg())
            val a = if (motion.reducedMotion) 1f else HudGeometry.smoothingAlpha((now - last).toFloat(), SMOOTH_TAU_MS)
            last = now
            var animating = false
            val seen = HashSet<Int>()
            val eased = raw.map { item ->
                val id = item.trackId
                val rect = item.rect
                if (id == null || rect == null || item.kind !in SMOOTHED) return@map item
                seen += id
                val next = smooth[id]?.let { HudGeometry.easeBox(it, rect, a) } ?: rect
                smooth[id] = next
                if (gap(next, rect) > CONVERGED_PX) animating = true
                item.copy(rect = next)
            }
            smooth.keys.retainAll(seen)
            val batch = autonomy.feed.batches.value[state.cameraId]
            val predicted = autonomy.feed.follow.value?.value?.predicted?.takeIf { it.cameraId == state.cameraId }
            state.frameW = batch?.frameWidth ?: predicted?.frameW ?: state.frameW
            state.frameH = batch?.frameHeight ?: predicted?.frameH ?: state.frameH
            state.items = eased
            state.nowMs = now
            if (!motion.reducedMotion && eased.any { it.kind == TargetKind.Searching || it.kind == TargetKind.Lost || TargetModifier.Reacquired in it.modifiers }) {
                animating = true
            }
            delay(if (animating) ANIMATING_FRAME_MS else IDLE_FRAME_MS)
        }
    }
}

private val SMOOTHED = setOf(TargetKind.Tracked, TargetKind.Locked, TargetKind.Acquiring, TargetKind.Following, TargetKind.Holding)

private fun gap(a: Box, b: Box) = max(max(abs(a.x - b.x), abs(a.y - b.y)), max(abs(a.w - b.w), abs(a.h - b.h)))
