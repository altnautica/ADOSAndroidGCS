package com.altnautica.gcs.core.extensions.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.theme.LocalAdosColors
import com.altnautica.gcs.core.extensions.marks.CockpitMark
import com.altnautica.gcs.core.extensions.marks.CockpitMarksParser
import com.altnautica.gcs.core.extensions.marks.CockpitMarksStore
import com.altnautica.gcs.core.extensions.marks.MarkFrame
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ExtensionMarksViewModel @Inject constructor(private val store: CockpitMarksStore) : ViewModel() {
    fun marks(node: DeviceId) = store.marks(node)
}

/**
 * Draws the vector marks extensions post with `cockpit.marks` (one composited layer for
 * every extension). Non-interactive; place it over the video at the marks z level.
 *
 * @param frame decoded video rect within this layer, in px; null when unknown, in which
 * case only normalized marks draw.
 */
@Composable
fun ExtensionMarksLayer(node: DeviceId, modifier: Modifier = Modifier, frame: MarkFrame? = null) {
    val vm: ExtensionMarksViewModel = hiltViewModel()
    val flow = remember(vm, node) { vm.marks(node) }
    val marks = flow.collectAsStateWithLifecycle(emptyList())
    // Composition follows only whether any mark is posted; the marks themselves are read by the draw pass.
    val any by remember { derivedStateOf { marks.value.isNotEmpty() } }
    if (!any) return
    MarksCanvas({ marks.value }, modifier, frame)
}

/**
 * The marks canvas. [marks] is read only in the draw pass, so a new set of marks redraws
 * this layer's own render node without recomposing anything; each set is laid out once
 * (positions, paths, strokes, label layouts) and replayed by every draw until it changes.
 */
@Composable
internal fun MarksCanvas(
    marks: () -> List<CockpitMark>,
    modifier: Modifier = Modifier,
    frame: MarkFrame? = null,
    recompositionProbe: (() -> Unit)? = null,
) {
    recompositionProbe?.let { probe -> SideEffect { probe() } }
    val defaultColor = LocalAdosColors.current.hudPrimary
    val measurer = rememberTextMeasurer()
    val painter = remember(measurer) { MarksPainter(measurer) }
    val current by rememberUpdatedState(marks)
    Spacer(
        modifier
            .graphicsLayer()
            .drawBehind { with(painter) { draw(current(), frame, defaultColor) } },
    )
}

/**
 * Lays out one set of marks for a canvas size and frame, then draws it. A draw of the same
 * set at the same size replays the laid-out marks and allocates nothing.
 */
internal class MarksPainter(private val measurer: TextMeasurer) {
    private var laidOut: List<CockpitMark>? = null
    private var laidFrame: MarkFrame? = null
    private var laidSize = Size.Unspecified
    private var laidDefault = Color.Unspecified
    private val shapes = ArrayList<LaidOutMark>()
    private val thin = Stroke(2f)
    private val dashed = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))

    fun DrawScope.draw(marks: List<CockpitMark>, frame: MarkFrame?, defaultColor: Color) {
        if (marks !== laidOut || frame != laidFrame || size != laidSize || defaultColor != laidDefault) {
            layOut(marks, frame, defaultColor)
            laidOut = marks
            laidFrame = frame
            laidSize = size
            laidDefault = defaultColor
        }
        for (i in shapes.indices) with(shapes[i]) { draw() }
    }

    private fun DrawScope.map(x: Double, y: Double, m: CockpitMark, frame: MarkFrame?): Offset? =
        CockpitMarksParser.mapPoint(x, y, m.space, size.width, size.height, frame)?.let { Offset(it.first, it.second) }

    private fun DrawScope.layOut(marks: List<CockpitMark>, frame: MarkFrame?, defaultColor: Color) {
        shapes.clear()
        for (m in marks) {
            val color = parseCssColor(m.color) ?: defaultColor
            when (m) {
                is CockpitMark.Box -> {
                    val a = map(m.x, m.y, m, frame) ?: continue
                    val b = map(m.x + m.width, m.y + m.height, m, frame) ?: continue
                    shapes += LaidOutMark.Rect(color, a, Size(b.x - a.x, b.y - a.y), if (m.dashed) dashed else thin)
                }
                is CockpitMark.Reticle -> {
                    val a = map(m.x, m.y, m, frame) ?: continue
                    val b = map(m.x + m.width, m.y + m.height, m, frame) ?: continue
                    val w = b.x - a.x
                    val h = b.y - a.y
                    shapes += LaidOutMark.Reticle(color, Offset(a.x + w / 2, a.y + h / 2), minOf(w, h) / 2, thin)
                }
                is CockpitMark.Point -> {
                    val p = map(m.x, m.y, m, frame) ?: continue
                    shapes += LaidOutMark.Dot(color, p, (m.radius ?: 4.0).toFloat())
                }
                is CockpitMark.Polyline -> {
                    val path = Path()
                    var started = false
                    for ((x, y) in m.points) {
                        val p = map(x, y, m, frame) ?: continue
                        if (!started) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        started = true
                    }
                    if (started) shapes += LaidOutMark.Line(color, path, Stroke((m.width ?: 2.0).toFloat()))
                }
                is CockpitMark.Label -> {
                    val p = map(m.x, m.y, m, frame) ?: continue
                    shapes += LaidOutMark.Text(measurer.measure(m.text, TextStyle(color = color, fontSize = 12.sp)), p)
                }
            }
        }
    }
}

/** One mark in canvas px, ready to draw. */
internal sealed interface LaidOutMark {
    fun DrawScope.draw()

    class Rect(private val color: Color, private val topLeft: Offset, private val size: Size, private val stroke: Stroke) : LaidOutMark {
        override fun DrawScope.draw() = drawRect(color, topLeft, size, style = stroke)
    }

    class Reticle(private val color: Color, private val c: Offset, private val arm: Float, private val stroke: Stroke) : LaidOutMark {
        override fun DrawScope.draw() {
            drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 2f)
            drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 2f)
            drawCircle(color, arm * 0.6f, c, style = stroke)
        }
    }

    class Dot(private val color: Color, private val at: Offset, private val radius: Float) : LaidOutMark {
        override fun DrawScope.draw() = drawCircle(color, radius, at)
    }

    class Line(private val color: Color, private val path: Path, private val stroke: Stroke) : LaidOutMark {
        override fun DrawScope.draw() = drawPath(path, color, style = stroke)
    }

    class Text(private val layout: TextLayoutResult, private val at: Offset) : LaidOutMark {
        override fun DrawScope.draw() = drawText(layout, topLeft = at)
    }
}

/** `#RGB`, `#RRGGBB` or `#RRGGBBAA`; anything else falls back to the HUD colour. */
internal fun parseCssColor(css: String?): Color? {
    val s = css?.trim()?.removePrefix("#") ?: return null
    val hex = when (s.length) {
        3 -> s.map { "$it$it" }.joinToString("") + "FF"
        6 -> s + "FF"
        8 -> s
        else -> return null
    }
    val v = hex.toLongOrNull(16) ?: return null
    val r = (v shr 24) and 0xFF
    val g = (v shr 16) and 0xFF
    val b = (v shr 8) and 0xFF
    val a = v and 0xFF
    return Color(r.toInt(), g.toInt(), b.toInt(), a.toInt())
}
