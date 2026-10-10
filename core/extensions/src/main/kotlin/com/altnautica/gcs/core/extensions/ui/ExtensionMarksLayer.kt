package com.altnautica.gcs.core.extensions.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val marks by flow.collectAsStateWithLifecycle(emptyList())
    if (marks.isEmpty()) return
    val defaultColor = LocalAdosColors.current.hudPrimary
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        for (m in marks) {
            val color = parseCssColor(m.color) ?: defaultColor
            when (m) {
                is CockpitMark.Box -> rect(m.x, m.y, m.width, m.height, m, frame)?.let { (o, s) ->
                    drawRect(
                        color, o, s,
                        style = Stroke(2f, pathEffect = if (m.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null),
                    )
                }
                is CockpitMark.Reticle -> rect(m.x, m.y, m.width, m.height, m, frame)?.let { (o, s) ->
                    val c = Offset(o.x + s.width / 2, o.y + s.height / 2)
                    val arm = minOf(s.width, s.height) / 2
                    drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 2f)
                    drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 2f)
                    drawCircle(color, arm * 0.6f, c, style = Stroke(2f))
                }
                is CockpitMark.Point -> map(m.x, m.y, m, frame)?.let { p ->
                    drawCircle(color, (m.radius ?: 4.0).toFloat(), p)
                }
                is CockpitMark.Polyline -> {
                    val path = Path()
                    var started = false
                    for ((x, y) in m.points) {
                        val p = map(x, y, m, frame) ?: continue
                        if (!started) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        started = true
                    }
                    if (started) drawPath(path, color, style = Stroke((m.width ?: 2.0).toFloat()))
                }
                is CockpitMark.Label -> map(m.x, m.y, m, frame)?.let { p ->
                    drawText(measurer, m.text, p, TextStyle(color = color, fontSize = 12.sp))
                }
            }
        }
    }
}

private fun DrawScope.map(x: Double, y: Double, m: CockpitMark, frame: MarkFrame?): Offset? =
    CockpitMarksParser.mapPoint(x, y, m.space, size.width, size.height, frame)?.let { Offset(it.first, it.second) }

private fun DrawScope.rect(x: Double, y: Double, w: Double, h: Double, m: CockpitMark, frame: MarkFrame?): Pair<Offset, Size>? {
    val a = map(x, y, m, frame) ?: return null
    val b = map(x + w, y + h, m, frame) ?: return null
    return a to Size(b.x - a.x, b.y - a.y)
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
