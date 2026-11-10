package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.skills.hud.ReadoutFormat
import com.altnautica.gcs.feature.cockpit.flow.specText
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A round flight-screen button on a blurred panel; [active] fills it with the accent. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Color? = null,
    onLongClick: (() -> Unit)? = null,
    /** A short word drawn under the icon (the left column's actions); null draws the icon alone. */
    shortLabel: String? = null,
) {
    val c = LocalChrome.current
    ChromePanel(modifier.size(size), shape = CircleShape, tint = active) {
        Box(
            Modifier
                .fillMaxSize()
                .combinedClickable(onClickLabel = label, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
                .semantics { contentDescription = label }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            if (shortLabel == null) {
                Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(size * 0.42f))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(size * 0.36f))
                    Text(
                        shortLabel,
                        style = c.labelText.copy(color = c.text, fontSize = c.labelText.fontSize * 0.85f),
                        maxLines = 1,
                        modifier = Modifier.testTag("$tag-label"),
                    )
                }
            }
        }
    }
}

/** The shutter: a white ring; in video mode a red dot that turns into a square while recording. */
@Composable
fun Shutter(video: Boolean, recording: Boolean, label: String, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    ChromePanel(modifier.size(size), shape = CircleShape) {
        Box(
            Modifier
                .fillMaxSize()
                .combinedClickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .testTag("grid-shutter"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(size - 10.dp).border(3.dp, c.text, CircleShape))
            when {
                video && recording -> Box(Modifier.size(size * 0.32f).background(c.critical, RoundedCornerShape(4.dp)))
                video -> Box(Modifier.size(size * 0.5f).background(c.critical, CircleShape))
                else -> Box(Modifier.size(size * 0.62f).background(c.text, CircleShape))
            }
        }
    }
}

/** Photo/video switch: two icons in one pill; the current mode is filled. */
@Composable
fun PhotoVideoSwitch(video: Boolean, videoEnabled: Boolean, photoLabel: String, videoLabel: String, onVideo: (Boolean) -> Unit, width: Dp, height: Dp) {
    val c = LocalChrome.current
    ChromePanel(Modifier.size(width, height), shape = RoundedCornerShape(height / 2)) {
        Row(Modifier.fillMaxSize().padding(3.dp)) {
            for (isVideo in listOf(false, true)) {
                val selected = isVideo == video
                val enabled = !isVideo || videoEnabled
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(height / 2))
                        .background(if (selected) c.text.copy(alpha = 0.9f) else Color.Transparent)
                        .combinedClickable(enabled = enabled, role = Role.RadioButton, onClickLabel = if (isVideo) videoLabel else photoLabel) { onVideo(isVideo) }
                        .semantics { contentDescription = if (isVideo) videoLabel else photoLabel }
                        .testTag(if (isVideo) "grid-mode-video" else "grid-mode-photo"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isVideo) Icons.Outlined.Videocam else Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        tint = when {
                            selected -> Color.Black
                            enabled -> c.text
                            else -> c.textDim.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Zoom: one vertical pill, in above, out below. */
@Composable
fun ZoomPill(inLabel: String, outLabel: String, onIn: () -> Unit, onOut: () -> Unit, onLongPress: (() -> Unit)?, width: Dp, height: Dp) {
    val c = LocalChrome.current
    ChromePanel(Modifier.size(width, height), shape = RoundedCornerShape(width / 2)) {
        Column(Modifier.fillMaxSize()) {
            for ((icon, label, action, tag) in listOf(
                Quad(Icons.Outlined.Add, inLabel, onIn, "grid-zoom-in"),
                Quad(Icons.Outlined.Remove, outLabel, onOut, "grid-zoom-out"),
            )) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .combinedClickable(onClickLabel = label, role = Role.Button, onLongClick = onLongPress, onClick = action)
                        .semantics { contentDescription = label }
                        .testTag(tag),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * The gimbal pad: drag the knob to aim the camera at a rate proportional to its offset;
 * letting go stops it, a double tap recentres. [onRate] receives pitch and yaw in -1..1
 * (up and right positive) while dragging, and (0, 0) on release.
 */
@Composable
fun GimbalPad(label: String, hint: String, size: Dp, onRate: (Float, Float) -> Unit, onRecenter: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val radiusPx = with(LocalDensity.current) { (size / 2 - 14.dp).toPx() }
    var knob by remember { mutableStateOf(Offset.Zero) }
    ChromePanel(modifier.size(size), shape = CircleShape) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "$label. $hint" }
                .testTag("grid-gimbal-pad")
                .pointerInput(radiusPx) {
                    detectDragGestures(
                        onDragEnd = { knob = Offset.Zero; onRate(0f, 0f) },
                        onDragCancel = { knob = Offset.Zero; onRate(0f, 0f) },
                    ) { change, drag ->
                        change.consume()
                        val next = knob + drag
                        val len = hypot(next.x, next.y)
                        knob = if (len > radiusPx) next * (radiusPx / len) else next
                        onRate(-knob.y / radiusPx, knob.x / radiusPx)
                    }
                }
                .combinedClickable(onClickLabel = label, role = Role.Button, onDoubleClick = onRecenter, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = this.size.width / 2
                val cy = this.size.height / 2
                val tick = 5.dp.toPx()
                val r = this.size.minDimension / 2 - 6.dp.toPx()
                for (a in 0 until 4) {
                    rotate(a * 90f, Offset(cx, cy)) {
                        drawLine(c.textDim, Offset(cx, cy - r), Offset(cx, cy - r + tick), 1.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            Box(
                Modifier
                    .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                    .size(24.dp)
                    .background(c.text.copy(alpha = 0.9f), CircleShape),
            )
        }
    }
}

/** The bottom row: D, H, H.S, V.S and AGL, tabular, one line each. AGL not measured above the ground names its reference. */
@Composable
fun BottomRow(values: FlightValues, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val cells = listOf(
        Cell("D", "cockpit.readout.distance", values.distanceM, ReadoutFormat.Kind.Distance),
        Cell("H", "cockpit.readout.height", values.heightM, ReadoutFormat.Kind.Metres),
        Cell("H.S", "cockpit.readout.hspeed", values.hSpeedMs, ReadoutFormat.Kind.Speed),
        Cell("V.S", "cockpit.readout.vspeed", values.vSpeedMs, ReadoutFormat.Kind.Speed),
        Cell("AGL", "cockpit.readout.agl", values.aglM, ReadoutFormat.Kind.Metres),
    )
    ChromePanel(modifier.fillMaxSize().testTag("bottom-row")) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = c.pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for ((tag, key, value, kind) in cells) {
                val (text, unit) = ReadoutFormat.readoutParts(kind, value?.toDouble())
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("value-$tag")) {
                    val label = specText(key)
                    val shown = if (tag == "AGL" && values.aglTag != null) "$label ${values.aglTag}" else label
                    Text(shown, style = c.labelText, modifier = Modifier.padding(end = 4.dp, bottom = 2.dp), maxLines = 1)
                    Text(text, style = c.valueText, maxLines = 1, overflow = TextOverflow.Clip)
                    Text(unit, style = c.labelText, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp), maxLines = 1)
                }
            }
        }
    }
}

/** One bottom-row readout: its test tag, spec label key, value and how it reads. */
private data class Cell(val tag: String, val key: String, val value: Float?, val kind: ReadoutFormat.Kind)

/**
 * The bottom-left card: one page at a time (map, attitude, obstacles), switched by a
 * horizontal swipe or a tap on its dots, both through [onStep]. The dots show the page.
 */
@Composable
fun SwitchCard(page: CardPage, label: String, onStep: (Int) -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.(CardPage) -> Unit) {
    val c = LocalChrome.current
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    ChromePanel(
        modifier
            .fillMaxSize()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> drag += delta },
                onDragStarted = { drag = 0f },
                onDragStopped = {
                    if (drag <= -threshold) onStep(1) else if (drag >= threshold) onStep(-1)
                    drag = 0f
                },
            )
            .testTag("grid-card"),
    ) {
        content(page)
        Text(
            label,
            style = c.labelText.copy(color = c.text),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(c.panelNoBlur, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Row(Modifier.align(Alignment.BottomCenter), verticalAlignment = Alignment.CenterVertically) {
            for (p in CardPage.entries) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clickable { if (p != page) onStep(p.ordinal - page.ordinal) }
                        .testTag("card-dot-${p.name}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(5.dp).background(if (p == page) c.text else c.textDim.copy(alpha = 0.5f), CircleShape))
                }
            }
        }
    }
}

/**
 * Attitude radar: the horizon disc (roll and pitch), the fixed aircraft mark and the
 * heading. Redraws from the attitude flow in the draw pass, without recomposing.
 */
@Composable
fun AttitudeRadar(attitude: StateFlow<Attitude?>?, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(attitude) { attitude?.collect { frame.longValue++ } }
    val path = remember { Path() }
    val sky = Color(0xFF2B5C8A)
    val ground = Color(0xFF6B4A2B)
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawBehind {
                frame.longValue
                val att = attitude?.value
                val roll = att?.let { Math.toDegrees(it.rollRad.toDouble()).toFloat() } ?: 0f
                val pitch = att?.let { Math.toDegrees(it.pitchRad.toDouble()).toFloat() } ?: 0f
                val r = size.minDimension / 2 - 10.dp.toPx()
                val center = Offset(size.width / 2, size.height / 2 + 4.dp.toPx())
                path.reset()
                path.addOval(androidx.compose.ui.geometry.Rect(center, r))
                clipPath(path) {
                    rotate(-roll, center) {
                        translate(0f, pitch / 45f * r) {
                            drawRect(sky.copy(alpha = 0.75f), Offset(center.x - r * 2, center.y - r * 3), androidx.compose.ui.geometry.Size(r * 4, r * 3))
                            drawRect(ground.copy(alpha = 0.75f), Offset(center.x - r * 2, center.y), androidx.compose.ui.geometry.Size(r * 4, r * 3))
                            drawLine(c.text, Offset(center.x - r * 2, center.y), Offset(center.x + r * 2, center.y), 1.5.dp.toPx())
                        }
                    }
                }
                drawCircle(c.border, r, center, style = Stroke(1.dp.toPx()))
                // The aircraft, fixed.
                val w = r * 0.45f
                drawLine(Color(0xFFFFC107), Offset(center.x - w, center.y), Offset(center.x - w * 0.3f, center.y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(Color(0xFFFFC107), Offset(center.x + w * 0.3f, center.y), Offset(center.x + w, center.y), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(Color(0xFFFFC107), 3.dp.toPx(), center)
                if (att == null) drawCircle(Color.Black.copy(alpha = 0.45f), r, center)
            }
            .testTag("attitude-radar"),
    )
}
