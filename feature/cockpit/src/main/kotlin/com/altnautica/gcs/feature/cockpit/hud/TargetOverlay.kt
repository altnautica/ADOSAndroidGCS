package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.designsystem.tokens.AdosHudTarget
import com.altnautica.gcs.feature.cockpit.autonomy.Box as FrameBox
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.DrawItem
import com.altnautica.gcs.feature.cockpit.autonomy.FollowEvent
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.TargetKind
import com.altnautica.gcs.feature.cockpit.autonomy.TargetModifier
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import com.altnautica.gcs.feature.cockpit.autonomy.TargetTag
import com.altnautica.gcs.feature.cockpit.autonomy.TargetText
import kotlin.math.max

/**
 * HUD v2 target symbology over one video: hairline candidates, corner brackets, the TRACK
 * tag, reticle, feet tick, coast ghost, search ring, off-frame arrow and the LOST fade.
 * The `targets` layer of the video canvas calls [draw] from its draw phase, so a new
 * resolve redraws without recomposing.
 */
@Stable
internal class TargetDrawer(
    private val colors: AdosColorRoles,
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val text: TargetText,
    private val motion: MotionPrefs,
    private val autonomy: CockpitAutonomy,
    private val compact: Boolean,
) {
    fun DrawScope.draw(state: TargetLayerState, alpha: Float) {
        val items = state.items
        if (items.isEmpty()) return
        val (left, top, scale) = state.fit(size.width, size.height)
        val view = Rect(left, top, left + state.frameW * scale, top + state.frameH * scale)
        val painter = TargetPainter(this, colors, measurer, style, text, motion, view, scale, state.nowMs, autonomy)
        val placed = ArrayList<Rect>()
        if (alpha < 1f) {
            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
            for (item in items) painter.draw(item, false, compact, placed)
            drawContext.canvas.restore()
        } else {
            for (item in items) painter.draw(item, false, compact, placed)
        }
    }
}

@Composable
internal fun rememberTargetDrawer(autonomy: CockpitAutonomy, motion: MotionPrefs, compact: Boolean): TargetDrawer {
    val colors = AdosTheme.colors
    val measurer = rememberTextMeasurer()
    val resources = LocalResources.current
    return remember(colors, measurer, resources, motion, autonomy, compact) {
        val style = AdosTypography.numeric.small.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight(AdosHudTarget.TagFontWeight),
            letterSpacing = AdosHudTarget.TagLetterSpacingEm.em,
        )
        TargetDrawer(colors, measurer, style, TargetText(resources), motion, autonomy, compact)
    }
}

/**
 * The targets' TalkBack nodes (one per drawn target, over its rendered rect, with its
 * spoken description and a select action) and the re-acquire haptic. Draws nothing.
 */
@Composable
internal fun TargetSemantics(
    state: TargetLayerState,
    autonomy: CockpitAutonomy,
    onSelect: (DrawItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = TargetText(LocalResources.current)
    val haptics = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(autonomy) {
        var seen = emptySet<FollowEvent>()
        autonomy.feed.events.collect { list ->
            if ((list - seen).isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            seen = list.toSet()
        }
    }
    val follow = autonomy.feed.follow.collectAsStateWithLifecycle().value?.value
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        val density = LocalDensity.current
        val (left, top, scale) = state.fit(size.width.toFloat(), size.height.toFloat())
        for (item in state.items) {
            val r = item.rect ?: continue
            val label = text.spoken(item, item.detection?.classLabel, follow?.rangeM?.takeIf { item.kind == TargetKind.Following })
            with(density) {
                Box(
                    Modifier
                        .offset((left + r.x * scale).toDp(), (top + r.y * scale).toDp())
                        .size((r.w * scale).toDp(), (r.h * scale).toDp())
                        .semantics {
                            contentDescription = label
                            if (item.detection != null) onClick { onSelect(item); true }
                        },
                )
            }
        }
    }
}

/** Paints one surface's items; holds the per-draw context. */
private class TargetPainter(
    private val scope: DrawScope,
    private val c: AdosColorRoles,
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val text: TargetText,
    private val motion: MotionPrefs,
    private val view: Rect,
    private val scale: Float,
    private val now: Long,
    private val autonomy: CockpitAutonomy,
) {
    private val d = scope.density
    private val stroke = AdosHudTarget.Stroke.value * d
    private val outline = AdosHudTarget.Outline.value * d
    private val outlineColor = AdosHudTarget.OutlineColor

    private fun toView(b: FrameBox) = Rect(view.left + b.x * scale, view.top + b.y * scale, view.left + b.right * scale, view.top + b.bottom * scale)

    fun draw(item: DrawItem, pip: Boolean, compact: Boolean, placed: MutableList<Rect>) = with(scope) {
        if (item.kind == TargetKind.SearchingOffFrame) {
            drawOffFrame(item)
            return@with
        }
        val rect = toView(item.rect ?: return@with)
        val alpha = when {
            item.kind == TargetKind.Lost -> lostAlpha()
            TargetModifier.Uncertain in item.modifiers -> 0.55f
            else -> 1f
        }
        val snap = snapScale(item)
        scale(snap, rect.center) {
            when (item.kind) {
                TargetKind.Candidate -> hairline(rect, c.hudInk.copy(alpha = 0.7f))
                TargetKind.Tracked -> brackets(rect, c.hudPrimary.copy(alpha = alpha), null)
                TargetKind.Locked, TargetKind.Acquiring, TargetKind.Following, TargetKind.Holding -> brackets(rect, c.hudTrack.copy(alpha = alpha), null)
                TargetKind.Coasting -> brackets(rect, c.hudCoast, PathEffect.dashPathEffect(floatArrayOf(4 * d, 3 * d)))
                TargetKind.Lost -> brackets(rect, c.hudCrit.copy(alpha = alpha), null)
                TargetKind.Searching -> searchRing(item, rect)
                TargetKind.SearchingOffFrame -> Unit
            }
        }
        if (item.kind == TargetKind.Following || item.kind == TargetKind.Holding) reticle(rect)
        if (item.kind == TargetKind.Following) feetTick(rect)
        if (item.kind == TargetKind.Coasting && !motion.lowPower) ghost(item)
        topTag(item, rect, pip, compact, placed, alpha)
        bottomTags(item, rect)
    }

    private fun lostAlpha(): Float {
        val start = autonomy.feed.events.value.lastOrNull { it is FollowEvent.Lost }?.atMs ?: return 1f
        if (motion.reducedMotion) return 1f
        return (1f - (now - start).toFloat() / TargetState.LOST_VISIBLE_MS).coerceIn(0f, 1f)
    }

    private fun snapScale(item: DrawItem): Float {
        if (TargetModifier.Reacquired !in item.modifiers || motion.reducedMotion) return 1f
        val at = autonomy.feed.events.value.lastOrNull { it is FollowEvent.Reacquired }?.atMs ?: return 1f
        val t = ((now - at).toFloat() / TargetState.REACQUIRED_SNAP_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return 1.15f - 0.15f * eased
    }

    private fun DrawScope.outlined(path: Path, color: Color, width: Float, effect: PathEffect?) {
        drawPath(path, outlineColor, style = Stroke(width + 2 * outline, cap = StrokeCap.Butt, pathEffect = effect))
        drawPath(path, color, style = Stroke(width, cap = StrokeCap.Butt, pathEffect = effect))
    }

    private fun DrawScope.hairline(r: Rect, color: Color) {
        drawRect(outlineColor, r.topLeft, r.size, style = Stroke(3 * d))
        drawRect(color, r.topLeft, r.size, style = Stroke(1 * d))
    }

    private fun DrawScope.brackets(r: Rect, color: Color, effect: PathEffect?) {
        val a = HudGeometry.bracketArm(r.width, r.height, AdosHudTarget.BracketRatio, AdosHudTarget.BracketMin.value * d, AdosHudTarget.BracketMax.value * d)
        val p = Path().apply {
            moveTo(r.left, r.top + a); lineTo(r.left, r.top); lineTo(r.left + a, r.top)
            moveTo(r.right - a, r.top); lineTo(r.right, r.top); lineTo(r.right, r.top + a)
            moveTo(r.right, r.bottom - a); lineTo(r.right, r.bottom); lineTo(r.right - a, r.bottom)
            moveTo(r.left + a, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.bottom - a)
        }
        outlined(p, color, stroke, effect)
    }

    private fun DrawScope.reticle(r: Rect) {
        val gap = AdosHudTarget.ReticleGap.value * d
        val arm = AdosHudTarget.ReticleArm.value * d
        if (r.width < 2 * (gap + arm) || r.height < 2 * (gap + arm)) return
        val cx = r.center.x
        val cy = r.center.y
        val p = Path().apply {
            moveTo(cx, cy - gap - arm); lineTo(cx, cy - gap)
            moveTo(cx, cy + gap); lineTo(cx, cy + gap + arm)
            moveTo(cx - gap - arm, cy); lineTo(cx - gap, cy)
            moveTo(cx + gap, cy); lineTo(cx + gap + arm, cy)
        }
        outlined(p, c.hudInk, 2 * d, null)
    }

    private fun DrawScope.feetTick(r: Rect) {
        val p = Path().apply { moveTo(r.center.x - 5 * d, r.bottom); lineTo(r.center.x + 5 * d, r.bottom) }
        outlined(p, c.hudTrack, stroke, null)
    }

    private fun DrawScope.ghost(item: DrawItem) {
        if (item.ghost.size < 2) return
        val p = Path()
        item.ghost.forEachIndexed { i, (x, y) ->
            val px = view.left + x * scale
            val py = view.top + y * scale
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, c.hudCoast.copy(alpha = 0.6f), style = Stroke(1.5f * d, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f * d, 3 * d))))
    }

    private fun DrawScope.searchRing(item: DrawItem, r: Rect) {
        val radius = max(24 * d, 0.6f * r.height)
        val center = Offset(r.center.x, r.bottom)
        val effect = if (motion.reducedMotion) PathEffect.dashPathEffect(floatArrayOf(4 * d, 3 * d)) else null
        drawCircle(outlineColor, radius, center, style = Stroke(1.5f * d + 2 * outline, pathEffect = effect))
        drawCircle(c.hudCoast, radius, center, style = Stroke(1.5f * d, pathEffect = effect))
        if (!motion.reducedMotion) {
            val sweep = (now % SWEEP_MS).toFloat() / SWEEP_MS * 360f
            drawArc(c.hudCoast, sweep - 90f, 90f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(4 * d))
        }
        // The SEARCHING tag sits above the ring rather than the predicted box.
        item.top?.let { plate(text.tag(it), Offset(center.x - radius, center.y - radius - 20 * d), c.hudGlass, c.hudCoast) }
    }

    private fun DrawScope.drawOffFrame(item: DrawItem) {
        val rel = item.relBearingDeg ?: return
        val (ax, ay) = HudGeometry.offFrameAnchor(rel, view.width, view.height, 16 * d)
        val tip = Offset(view.left + ax, view.top + ay)
        rotate(rel, tip) {
            val p = Path().apply {
                moveTo(tip.x, tip.y); lineTo(tip.x - 7 * d, tip.y + 14 * d); lineTo(tip.x + 7 * d, tip.y + 14 * d); close()
            }
            outlined(p, c.hudCoast, 1.5f * d, null)
        }
        // The tag sits inward of the arrow, toward the centre.
        val label = item.top?.let { text.tag(it) } ?: return
        val m = measurer.measure(label, style)
        val inward = Offset(view.center.x - tip.x, view.center.y - tip.y).let { o -> val len = max(1f, o.getDistance()); Offset(o.x / len, o.y / len) }
        val at = tip + inward * (28 * d) - Offset(m.size.width / 2f, m.size.height / 2f)
        plate(label, clampInto(at, m.size.width.toFloat(), m.size.height.toFloat()), c.hudGlass, c.hudCoast)
    }

    private fun DrawScope.topTag(item: DrawItem, r: Rect, pip: Boolean, compact: Boolean, placed: MutableList<Rect>, alpha: Float) {
        if (item.kind == TargetKind.Searching) return
        val tag = item.top ?: return
        if (item.kind == TargetKind.Candidate && (pip || compact || r.height < 24 * d)) return
        val label = if (compact && tag is TargetTag.Tracked) "#${tag.id}" else text.tag(tag)
        val (fill, ink) = tagColors(item.kind, tag)
        val m = measurer.measure(label, style)
        val w = m.size.width + 2 * pad()
        val h = m.size.height + pad()
        var y = r.top - 3 * d - h
        if (y < view.top) y = r.top + 2 * d
        val x = r.left.coerceIn(view.left, max(view.left, view.right - w))
        val box = Rect(x, y, x + w, y + h)
        if (placed.any { it.overlaps(box) }) return
        placed += box
        plate(label, Offset(x, y), fill.copy(alpha = fill.alpha * alpha), ink.copy(alpha = ink.alpha * alpha))
    }

    private fun DrawScope.bottomTags(item: DrawItem, r: Rect) {
        if (item.bottom.isEmpty()) return
        val parts = item.bottom.map { t ->
            val label = text.tag(t)
            val ink = when {
                t is TargetTag.FollowStale -> c.hudWarn
                t is TargetTag.Sigma -> c.hudMuted
                item.kind == TargetKind.Coasting -> c.hudCoast
                else -> c.hudInk
            }
            Triple(label, measurer.measure(label, style), ink)
        }
        val gap = 4 * d
        val total = parts.sumOf { (it.second.size.width + 2 * pad()).toDouble() }.toFloat() + gap * (parts.size - 1)
        val h = parts.maxOf { it.second.size.height } + pad()
        var y = r.bottom + 3 * d
        if (y + h > view.bottom) y = r.bottom - h - 2 * d
        var x = (r.center.x - total / 2).coerceIn(view.left, max(view.left, view.right - total))
        for ((label, m, ink) in parts) {
            plate(label, Offset(x, y), c.hudGlass, ink)
            x += m.size.width + 2 * pad() + gap
        }
    }

    private fun tagColors(kind: TargetKind, tag: TargetTag): Pair<Color, Color> = when {
        tag is TargetTag.Track -> c.hudTrack to Color.Black
        tag is TargetTag.Hold -> c.hudWarn.copy(alpha = 0.85f) to Color.Black
        kind == TargetKind.Candidate -> c.hudGlass to c.hudInk
        kind == TargetKind.Tracked -> c.hudGlass to c.hudPrimary
        kind == TargetKind.Coasting -> c.hudGlass to c.hudCoast
        kind == TargetKind.Lost -> c.hudGlass to c.hudCrit
        else -> c.hudGlass to c.hudTrack
    }

    private fun pad() = AdosHudTarget.TagPad.value * d

    private fun clampInto(o: Offset, w: Float, h: Float) =
        Offset(o.x.coerceIn(view.left, max(view.left, view.right - w)), o.y.coerceIn(view.top, max(view.top, view.bottom - h)))

    private fun DrawScope.plate(label: String, at: Offset, fill: Color, ink: Color) {
        val m = measurer.measure(label, style.copy(color = ink))
        val w = m.size.width + 2 * pad()
        val h = m.size.height + pad()
        drawRoundRect(fill, at, Size(w, h), CornerRadius(4 * d))
        drawText(m, topLeft = Offset(at.x + pad(), at.y + pad() / 2))
    }

    private companion object {
        const val SWEEP_MS = 1_200L
    }
}
