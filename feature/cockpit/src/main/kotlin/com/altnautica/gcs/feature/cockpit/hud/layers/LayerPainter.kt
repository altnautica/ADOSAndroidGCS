package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Layer colours (cockpit spec `tokens.json`: `semantic.ar.*`, `semantic.layer.*`). */
object LayerColors {
    val Route = Color(0xFFFF3EC8)
    val Future = Color.White.copy(alpha = 0.8f)
    val Home = Color(0xFFFFD400)
    val Follow = Color(0xFF22D3EE)
    val Detour = Color(0xFFF59E0B) // primitive.color.amber.500
    val Blocked = Color(0xFFEF4444) // primitive.color.red.500
    val Halo = Color.Black.copy(alpha = 0.6f)
    val Paused = Color(0xFF9CA3AF)
    val Near = Blocked.copy(alpha = 0.45f) // obstacles ≤ 2 m
    val Mid = Detour.copy(alpha = 0.30f) // obstacles 2–5 m
    val Zone = Color(0xFF22C55E)
    val ChipFill = Color.Black.copy(alpha = 0.55f)
}

/** How grid cells draw: filled when measured, outlined otherwise, or every cell outlined. */
enum class CellStyle { Solid, Outline }

/**
 * Draws every data layer into one canvas. One instance lives per [LayerStack] and keeps
 * its paths, grid buffers and label layouts across frames: the draw phase allocates
 * nothing except the first layout of a new label text.
 */
class LayerPainter(private val measurer: TextMeasurer) {
    private val path = Path()
    private val halo = Path()
    private val near = ByteArray(MAX_CELLS)
    private val measured = ByteArray(MAX_CELLS / 8 + 1)
    private val labels = HashMap<String, TextLayoutResult>()
    private val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    private val prevByKey = HashMap<String, ArItem>()
    private val curByKey = HashMap<String, ArItem>()
    private var keyedFrame: ArCamera? = null
    private var keyedPrev: ArCamera? = null
    private val dashed = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    private val dotted = PathEffect.dashPathEffect(floatArrayOf(2f, 10f))

    /** Label layouts measured once per text value; bounded so stale ETA texts do not pile up. */
    private fun label(text: String): TextLayoutResult {
        labels[text]?.let { return it }
        if (labels.size > MAX_LABELS) labels.clear()
        return measurer.measure(text, labelStyle).also { labels[text] = it }
    }

    fun DrawScope.chip(text: String, at: Offset, ink: Color = Color.White, alpha: Float = 1f) {
        val l = label(text)
        val pad = px(4f)
        drawRoundRect(LayerColors.ChipFill, at, Size(l.size.width + 2 * pad, l.size.height + pad), androidx.compose.ui.geometry.CornerRadius(pad), alpha = alpha)
        drawText(l, color = ink, topLeft = Offset(at.x + pad, at.y + pad / 2), alpha = alpha)
    }

    private fun DrawScope.px(v: Float) = v * density

    private fun DrawScope.haloStroke(p: Path, color: Color, width: Float, alpha: Float, effect: PathEffect? = null) {
        drawPath(p, LayerColors.Halo, alpha = alpha, style = Stroke(width + px(4f), cap = StrokeCap.Round, pathEffect = effect))
        drawPath(p, color, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
    }

    // ---- AR -----------------------------------------------------------------------------

    /** Re-keys the current and previous items when a new frame arrived (once per frame, not per draw). */
    private fun rekey(cur: ArCamera?, prev: ArCamera?) {
        if (cur === keyedFrame && prev === keyedPrev) return
        keyedFrame = cur
        keyedPrev = prev
        curByKey.clear()
        cur?.items?.forEach { curByKey[it.key] = it }
        prevByKey.clear()
        prev?.items?.forEach { prevByKey[it.key] = it }
    }

    /**
     * The AR items of [cameraId]: each item drawn between its last two positions (same key)
     * at [now]; new items fade in and removed items fade out over 120 ms.
     */
    fun DrawScope.drawAr(slot: LayerSlot<ArFrame>, cameraId: String, view: Rect, flags: LayerFlags, route: RouteStyle?, now: Long, layerAlpha: Float) {
        val frame = slot.cur ?: return
        val cur = frame.cameras[cameraId]
        val prev = slot.prev?.cameras?.get(cameraId)
        rekey(cur, prev)
        val f = slot.fraction(now)
        val fade = ((now - slot.curAt).toFloat() / LayerSlot.FADE_MS).coerceIn(0f, 1f)
        cur?.items?.forEach { item ->
            if (!ArRules.include(item, flags, route)) return@forEach
            val before = prevByKey[item.key]
            val a = if (before == null && slot.prev != null) fade else 1f
            item(item, before, f, view, route, layerAlpha * a)
        }
        if (fade < 1f) {
            prevByKey.values.forEach { gone ->
                if (curByKey.containsKey(gone.key) || !ArRules.include(gone, flags, route)) return@forEach
                item(gone, null, 1f, view, route, layerAlpha * (1f - fade))
            }
        }
    }

    private fun lerp(a: Float?, b: Float?, f: Float): Float? = when {
        b == null -> null
        a == null -> b
        else -> a + (b - a) * f
    }

    private fun DrawScope.pt(view: Rect, x: Float, y: Float) = Offset(view.left + x * view.width, view.top + y * view.height)

    /** Builds [path] from [cur]'s points, interpolated toward from [before]'s when the counts match. */
    private fun DrawScope.polyline(cur: ArItem, before: ArItem?, f: Float, view: Rect, close: Boolean = false): Boolean {
        path.rewind()
        val pts = cur.pts
        if (pts.size < 2) return false
        val b = before?.pts?.takeIf { it.size == pts.size }
        for (i in pts.indices) {
            val p = pts[i]
            if (p.size < 2) continue
            val x = b?.get(i)?.getOrNull(0)?.let { it + (p[0] - it) * f } ?: p[0]
            val y = b?.get(i)?.getOrNull(1)?.let { it + (p[1] - it) * f } ?: p[1]
            val o = pt(view, x, y)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        if (close) path.close()
        return true
    }

    private fun DrawScope.item(it: ArItem, before: ArItem?, f: Float, view: Rect, route: RouteStyle?, alpha: Float) {
        if (alpha <= 0.01f) return
        val x = lerp(before?.x, it.x, f)
        val y = lerp(before?.y, it.y, f)
        val routeColor = when (route) {
            RouteStyle.Paused -> LayerColors.Paused
            RouteStyle.Preview -> LayerColors.Future
            else -> LayerColors.Route
        }
        val routeEffect = if (route == RouteStyle.Paused || route == RouteStyle.Preview) dashed else null
        when (it.t) {
            "leg" -> {
                if (!polyline(it, before, f, view)) return
                when {
                    route == RouteStyle.Normal && it.role == "active" -> {
                        drawPath(path, LayerColors.Route, alpha = alpha * 0.35f, style = Stroke(px(6f), cap = StrokeCap.Round))
                        haloStroke(path, LayerColors.Route, px(2f), alpha)
                    }
                    route == RouteStyle.Normal && it.role == "next" -> haloStroke(path, Color.White, px(2f), alpha)
                    route == RouteStyle.Normal -> haloStroke(path, LayerColors.Future, px(1.5f), alpha * 0.65f, dashed)
                    else -> haloStroke(path, routeColor, px(1.5f), alpha, routeEffect)
                }
            }
            "wp" -> {
                if (x == null || y == null) return
                val c = pt(view, x, y)
                if (it.role == "other") {
                    drawCircle(routeColor, px(3f), c, alpha = alpha * 0.4f)
                    return
                }
                val gx = lerp(before?.gx, it.gx, f)
                val gy = lerp(before?.gy, it.gy, f)
                if (gx != null && gy != null && it.role == "next") {
                    path.rewind()
                    path.moveTo(c.x, c.y)
                    val g = pt(view, gx, gy)
                    path.lineTo(g.x, g.y)
                    haloStroke(path, routeColor, px(1f), alpha * 0.7f, dotted)
                }
                val s = px(ArRules.markerDp(it.size)) / 2
                path.rewind()
                path.moveTo(c.x, c.y + s * 0.6f)
                path.lineTo(c.x - s * 0.7f, c.y - s * 0.6f)
                path.lineTo(c.x + s * 0.7f, c.y - s * 0.6f)
                path.close()
                val hollow = it.role == "after" || route == RouteStyle.Paused || route == RouteStyle.Preview
                if (!hollow) drawPath(path, routeColor, alpha = alpha, style = Fill)
                haloStroke(path, routeColor, px(2f), alpha, if (it.role == "after") dashed else null)
                if (it.role == "next") chip(ArRules.wpChip(it.seq, it.distM, it.etaS.takeIf { route == RouteStyle.Normal }), Offset(c.x + s, c.y - s), alpha = alpha)
            }
            "home" -> {
                if (x == null || y == null) return
                val c = pt(view, x, y)
                val emph = route == RouteStyle.Returning
                val r = px(if (emph) 18f else 12f)
                drawCircle(LayerColors.Halo, r + px(2f), c, alpha = alpha)
                drawCircle(LayerColors.Home, r, c, alpha = alpha, style = Stroke(px(if (emph) 3f else 2f)))
                val l = label("H")
                drawText(l, color = LayerColors.Home, topLeft = Offset(c.x - l.size.width / 2f, c.y - l.size.height / 2f), alpha = alpha)
                if (emph) it.distM?.let { d -> chip("H · ${Math.round(d)} m", Offset(c.x + r + px(4f), c.y - r), LayerColors.Home, alpha) }
            }
            "fpv" -> {
                if (x == null || y == null) return
                val c = pt(view, x, y)
                val r = px(6f)
                drawCircle(LayerColors.Halo, r + px(2f), c, alpha = alpha, style = Stroke(px(4f)))
                drawCircle(Color.White, r, c, alpha = alpha, style = Stroke(px(2f)))
                path.rewind()
                path.moveTo(c.x - px(18f) / 2 - r, c.y)
                path.lineTo(c.x - r, c.y)
                path.moveTo(c.x + r, c.y)
                path.lineTo(c.x + r + px(18f) / 2, c.y)
                path.moveTo(c.x, c.y - r)
                path.lineTo(c.x, c.y - r - px(5f))
                haloStroke(path, Color.White, px(2f), alpha)
            }
            "fpath" -> {
                if (!polyline(it, before, f, view)) return
                val color = when (it.state) {
                    "detour" -> LayerColors.Detour
                    "blocked" -> LayerColors.Blocked
                    else -> LayerColors.Follow
                }
                haloStroke(path, color, px(2f), alpha, dashed)
                if (it.state == "blocked") {
                    val p0 = it.pts.first()
                    val c = pt(view, p0[0], p0[1])
                    val s = px(8f)
                    path.rewind()
                    path.moveTo(c.x - s, c.y - s)
                    path.lineTo(c.x + s, c.y + s)
                    path.moveTo(c.x + s, c.y - s)
                    path.lineTo(c.x - s, c.y + s)
                    haloStroke(path, LayerColors.Blocked, px(3f), alpha)
                }
            }
            "fgoal" -> {
                if (x == null || y == null) return
                val c = pt(view, x, y)
                drawCircle(LayerColors.Halo, px(9f), c, alpha = alpha, style = Stroke(px(4f)))
                drawCircle(LayerColors.Follow, px(9f), c, alpha = alpha, style = Stroke(px(2f)))
                drawCircle(LayerColors.Follow, px(2.5f), c, alpha = alpha)
            }
            "orbit" -> {
                if (!polyline(it, before, f, view, close = it.pts.size > 8)) return
                haloStroke(path, LayerColors.Follow, px(2f), alpha)
                chevrons(it, view, alpha)
            }
            "edge" -> {
                val ang = lerp(before?.ang, it.ang, f) ?: return
                val inset = px(16f)
                val cx = view.center.x
                val cy = view.center.y
                val dx = cos(ang)
                val dy = sin(ang)
                val tx = if (kotlin.math.abs(dx) < 1e-4f) Float.MAX_VALUE else ((if (dx > 0) view.right - inset else view.left + inset) - cx) / dx
                val ty = if (kotlin.math.abs(dy) < 1e-4f) Float.MAX_VALUE else ((if (dy > 0) view.bottom - inset else view.top + inset) - cy) / dy
                val t = min(tx, ty)
                val tip = Offset(cx + dx * t, cy + dy * t)
                val s = px(20f)
                path.rewind()
                path.moveTo(tip.x, tip.y)
                path.lineTo(tip.x - dx * s - dy * s * 0.5f, tip.y - dy * s + dx * s * 0.5f)
                path.lineTo(tip.x - dx * s + dy * s * 0.5f, tip.y - dy * s - dx * s * 0.5f)
                path.close()
                val color = if (it.of == "home") LayerColors.Home else routeColor
                drawPath(path, LayerColors.Halo, alpha = alpha, style = Stroke(px(4f)))
                drawPath(path, color, alpha = alpha)
                val text = (if (it.of == "home") "H" else "WP${it.seq ?: ""}") + (it.distM?.let { d -> " · ${Math.round(d)} m" } ?: "")
                val l = label(text)
                val lx = (tip.x - dx * s * 2 - l.size.width / 2f).coerceIn(view.left, view.right - l.size.width)
                val ly = (tip.y - dy * s * 2 - l.size.height / 2f).coerceIn(view.top, view.bottom - l.size.height)
                chip(text, Offset(lx, ly), color, alpha)
            }
        }
    }

    /** Three direction chevrons on an orbit ring at a third of the ring each. */
    private fun DrawScope.chevrons(it: ArItem, view: Rect, alpha: Float) {
        val pts = it.pts
        if (pts.size < 6) return
        val sign = if (it.dir == "ccw") -1 else 1
        for (k in 0 until 3) {
            val i = k * pts.size / 3
            val a = pts[i]
            val b = pts[Math.floorMod(i + sign, pts.size)]
            val pa = pt(view, a[0], a[1])
            val pb = pt(view, b[0], b[1])
            val len = max(1e-3f, (pb - pa).getDistance())
            val ux = (pb.x - pa.x) / len
            val uy = (pb.y - pa.y) / len
            val s = px(6f)
            path.rewind()
            path.moveTo(pa.x - ux * s - uy * s, pa.y - uy * s + ux * s)
            path.lineTo(pa.x, pa.y)
            path.lineTo(pa.x - ux * s + uy * s, pa.y - uy * s - ux * s)
            haloStroke(path, LayerColors.Follow, px(2f), alpha)
        }
    }

    // ---- Grids (obstacles, depth) ------------------------------------------------------------

    /**
     * One camera grid: cells nearer than [maxM] filled red (≤ 2 m) or amber (2–5 m), solid
     * when measured and outlined otherwise ([CellStyle.Outline] outlines every cell);
     * unknown cells never draw.
     */
    fun DrawScope.drawGrid(grid: CameraGrid, view: Rect, maxM: Float, style: CellStyle, alpha: Float) {
        val cells = grid.cols * grid.rows
        if (cells <= 0 || cells > MAX_CELLS) return
        if (GridCodec.decode(grid.near, near) < cells) return
        val hasMask = GridCodec.decode(grid.measured, measured) >= (cells + 7) / 8
        val cw = view.width / grid.cols
        val ch = view.height / grid.rows
        val outline = Stroke(px(1f))
        for (i in 0 until cells) {
            val v = near[i].toInt() and 0xFF
            if (v == 0) continue
            val m = GridCodec.metres(v)
            if (m > maxM) continue
            val color = if (m <= 2f) LayerColors.Near else LayerColors.Mid
            val col = i % grid.cols
            val row = i / grid.cols
            val tl = Offset(view.left + col * cw, view.top + row * ch)
            val size = Size(cw, ch)
            val solid = style == CellStyle.Solid && hasMask && GridCodec.bit(measured, i)
            if (solid) {
                drawRect(color, tl, size, alpha = alpha)
            } else {
                drawRect(color.copy(alpha = 1f), tl, size, alpha = alpha * 0.8f, style = outline)
            }
        }
    }

    /** The bottom-centre arc of the 72 body sectors within ±60° of the nose, with the nearest distance. */
    fun DrawScope.drawSectorArc(sectors: List<Float?>, view: Rect, alpha: Float) {
        if (sectors.size != SECTORS) return
        val r = min(view.width, view.height) * 0.22f
        val c = Offset(view.center.x, view.bottom - px(12f))
        var nearest = Float.MAX_VALUE
        for (i in -12..12) {
            val d = sectors[Math.floorMod(i, SECTORS)] ?: continue
            nearest = min(nearest, d)
            if (d > 5f) continue
            val color = if (d <= 2f) LayerColors.Blocked else LayerColors.Detour
            val start = -90f + i * 5f - 2.5f
            drawArc(color, start, 5f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), alpha = alpha, style = Stroke(px(6f)))
        }
        if (nearest < Float.MAX_VALUE) {
            val text = String.format(java.util.Locale.ROOT, "%.1f m", nearest)
            val l = label(text)
            val ink = if (nearest <= 2f) LayerColors.Blocked else if (nearest <= 5f) LayerColors.Detour else Color.White
            chip(text, Offset(c.x - l.size.width / 2f - px(4f), c.y - r - l.size.height - px(10f)), ink, alpha)
        }
    }

    // ---- Surfaces --------------------------------------------------------------------------------

    fun DrawScope.drawSurfaces(frame: SurfacesFrame, view: Rect, alpha: Float) {
        for (z in frame.zones) {
            if (z.pts.size < 3) continue
            path.rewind()
            z.pts.forEachIndexed { i, p ->
                val o = pt(view, p[0], p[1])
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
            drawPath(path, LayerColors.Zone, alpha = alpha * 0.15f)
            haloStroke(path, LayerColors.Zone, px(2f), alpha, dashed)
            val p0 = z.pts[0]
            chip("${Math.round(z.score * 100)}", pt(view, p0[0], p0[1]), LayerColors.Zone, alpha)
        }
    }

    companion object {
        const val SECTORS = 72
        const val MAX_CELLS = 32 * 18 * 4
        const val MAX_LABELS = 128
    }
}
