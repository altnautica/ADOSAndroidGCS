package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.PadSnapshot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import kotlin.math.hypot

/** Thumb pad size of the on-screen sticks. */
val ON_SCREEN_PAD: Dp = 120.dp

/**
 * One on-screen thumb pad. It reports its deflection in [-1, 1] (x right, y up) while a
 * finger holds it and springs back to centre on release, reporting (0, 0).
 */
@Composable
fun ThumbPad(label: String, onDeflect: (x: Float, y: Float) -> Unit, onTouch: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .size(ON_SCREEN_PAD)
            .semantics { contentDescription = label }
            .testTag("thumb-pad")
            .pointerInput(Unit) {
                val r = size.width / 2f
                val c = Offset(r, size.height / 2f)
                fun report(p: Offset) {
                    var d = p - c
                    val len = hypot(d.x, d.y)
                    if (len > r) d *= r / len
                    knob = d / r
                    onDeflect(knob.x, -knob.y)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onTouch(true)
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        report(change.position)
                        change.consume()
                    }
                    knob = Offset.Zero
                    onDeflect(0f, 0f)
                    onTouch(false)
                }
            },
    ) {
        Canvas(Modifier.size(ON_SCREEN_PAD)) {
            val r = size.minDimension / 2f
            drawCircle(colors.overlayPanel, r)
            drawCircle(colors.borderStrong, r, style = Stroke(2.dp.toPx()))
            drawCircle(colors.accentPrimary, r * 0.32f, center + knob * (r * 0.68f))
        }
    }
}

/**
 * The on-screen sticks as a controller: the left pad is the left stick, the right pad the
 * right stick, so the stick mode, dead zone and streamer stop rules apply unchanged. The
 * pad reads fresh only while a finger rests on a pad; lifting both fingers lets it go
 * stale, which releases the stick session and the pilot holds.
 */
class OnScreenSticks {
    @Volatile private var left = 0f to 0f
    @Volatile private var right = 0f to 0f
    @Volatile private var fingers = 0

    fun setLeft(x: Float, y: Float) {
        left = x to y
    }

    fun setRight(x: Float, y: Float) {
        right = x to y
    }

    fun touch(down: Boolean) {
        fingers = (fingers + if (down) 1 else -1).coerceAtLeast(0)
    }

    /** The pads as a pad snapshot at [nowMs]; y up is negative, as a controller reports it. */
    fun snapshot(nowMs: Long): PadSnapshot {
        val axes = GamepadAxes(left.first, -left.second, right.first, -right.second)
        return PadSnapshot(
            deviceId = if (fingers > 0) DEVICE_ID else null,
            vendorId = 0,
            productId = 0,
            raw = axes,
            axes = axes,
            buttons = 0,
            atMs = if (fingers > 0) nowMs else 0L,
            touches = emptyList(),
        )
    }

    companion object {
        /** Pad id the on-screen sticks report; real devices have non-negative ids. */
        const val DEVICE_ID = -2
    }
}
