package com.altnautica.gcs.feature.cockpit.hud

import com.altnautica.gcs.feature.cockpit.chrome.rememberOnBeat
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
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
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
    /** Tag rects placed this draw; cleared and reused, so a steady draw allocates no list. */
    private val placed = ArrayList<Rect>()
    private val layerPaint = androidx.compose.ui.graphics.Paint()

    /** One painter for the drawer's life: its path, strokes and tag layouts are kept across draws. */
    private val painter = TargetPainter(colors, measurer, style, text, motion, autonomy)

    /** Canvas y (px) above which no tag draws: the top bar's bottom edge when the video runs under it. */
    var topClearPx: Float = 0f

    fun DrawScope.draw(state: TargetLayerState, alpha: Float) {
        val items = state.items
        if (items.isEmpty()) return
        val (left, top, scale) = state.fit(size.width, size.height)
        drawItems(items, Rect(left, top, left + state.frameW * scale, top + state.frameH * scale), scale, state.nowMs, alpha)
    }

    /** Draws [items] into [view], the letterboxed frame at [scale] canvas px per frame px. */
    internal fun DrawScope.drawItems(items: List<DrawItem>, view: Rect, scale: Float, nowMs: Long, alpha: Float) {
        painter.begin(density, view, scale, nowMs, topClearPx)
        placed.clear()
        if (alpha < 1f) {
            layerPaint.alpha = alpha
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layerPaint)
            for (i in items.indices) with(painter) { draw(items[i], false, compact, placed) }
            drawContext.canvas.restore()
        } else {
            for (i in items.indices) with(painter) { draw(items[i], false, compact, placed) }
        }
    }
}

@Composable
internal fun rememberTargetDrawer(autonomy: CockpitAutonomy, motion: MotionPrefs, compact: Boolean): TargetDrawer {
    val colors = AdosTheme.colors
    val measurer = rememberTextMeasurer()
    val strings = LocalCockpitStrings.current
    return remember(colors, measurer, strings, motion, autonomy, compact) {
        val style = AdosTypography.numeric.small.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight(AdosHudTarget.TagFontWeight),
            letterSpacing = AdosHudTarget.TagLetterSpacingEm.em,
        )
        TargetDrawer(colors, measurer, style, TargetText(strings), motion, autonomy, compact)
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
    val text = TargetText(LocalCockpitStrings.current)
    val haptics = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(autonomy) {
        var seen = emptySet<FollowEvent>()
        autonomy.feed.events.collect { list ->
            if ((list - seen).isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            seen = list.toSet()
        }
    }
    // The follow range (up to 20 Hz) is read on the beat, and only while a followed box is spoken.
    val followRangeM by rememberOnBeat {
        autonomy.feed.follow.value?.value?.rangeM?.takeIf { state.spoken.any { it.kind == TargetKind.Following } }
    }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        val density = LocalDensity.current
        val (left, top, scale) = state.fit(size.width.toFloat(), size.height.toFloat())
        for (item in state.spoken) {
            val r = item.rect ?: continue
            val label = text.spoken(item, item.detection?.classLabel, followRangeM?.takeIf { item.kind == TargetKind.Following })
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

/**
 * Paints one surface's items. One instance lives per [TargetDrawer]; [begin] sets the draw's
 * context, and the path, strokes, dash effects and tag layouts are kept across draws, so a
 * steady draw allocates none of them.
 */
private class TargetPainter(
    private val c: AdosColorRoles,
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val text: TargetText,
    private val motion: MotionPrefs,
    private val autonomy: CockpitAutonomy,
) {
    private var view = Rect.Zero
    private var scale = 1f
    private var now = 0L
    private var topClear = 0f
    private var d = 1f
    private var stroke = 0f
    private var outline = 0f
    private val outlineColor = AdosHudTarget.OutlineColor
    private val path = Path()
    private val strokes = ArrayList<Stroke>()
    private val tags = HashMap<TargetTag, TextLayoutResult>()
    private val compactTags = HashMap<TargetTag, TextLayoutResult>()
    private var coastDash: PathEffect? = null
    private var ghostDash: PathEffect? = null

    /** Sets the context of one draw; a density change rebuilds the density-scaled strokes and dashes. */
    fun begin(density: Float, view: Rect, scale: Float, now: Long, topClear: Float) {
        if (density != d || coastDash == null) {
            d = density
            strokes.clear()
            coastDash = PathEffect.dashPathEffect(floatArrayOf(4 * d, 3 * d))
            ghostDash = PathEffect.dashPathEffect(floatArrayOf(1.5f * d, 3 * d))
        }
        stroke = AdosHudTarget.Stroke.value * d
        outline = AdosHudTarget.Outline.value * d
        this.view = view
        this.scale = scale
        this.now = now
        this.topClear = topClear
    }

    /** A stroke style, built once per width, cap and effect. */
    private fun strokeOf(width: Float, cap: StrokeCap = StrokeCap.Butt, effect: PathEffect? = null): Stroke {
        for (i in strokes.indices) {
            val s = strokes[i]
            if (s.width == width && s.cap == cap && s.pathEffect === effect) return s
        }
        if (strokes.size >= MAX_STROKES) strokes.clear()
        return Stroke(width, cap = cap, pathEffect = effect).also { strokes += it }
    }

    /** A tag's layout, keyed by the tag itself, so a steady draw neither formats nor measures its text; drawn in any ink. */
    private fun tagLayout(tag: TargetTag): TextLayoutResult {
        tags[tag]?.let { return it }
        if (tags.size >= MAX_TAGS) tags.clear()
        return measurer.measure(text.tag(tag), style).also { tags[tag] = it }
    }

    /** The compact layout of a tracked tag: its id only. */
    private fun compactLayout(tag: TargetTag.Tracked): TextLayoutResult {
        compactTags[tag]?.let { return it }
        if (compactTags.size >= MAX_TAGS) compactTags.clear()
        return measurer.measure("#${tag.id}", style).also { compactTags[tag] = it }
    }

    private fun toView(b: FrameBox) = Rect(view.left + b.x * scale, view.top + b.y * scale, view.left + b.right * scale, view.top + b.bottom * scale)

    fun DrawScope.draw(item: DrawItem, pip: Boolean, compact: Boolean, placed: MutableList<Rect>) {
        if (item.kind == TargetKind.SearchingOffFrame) {
            drawOffFrame(item)
            return
        }
        val rect = toView(item.rect ?: return)
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
                TargetKind.Coasting -> brackets(rect, c.hudCoast, coastDash)
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
        drawPath(path, outlineColor, style = strokeOf(width + 2 * outline, StrokeCap.Butt, effect))
        drawPath(path, color, style = strokeOf(width, StrokeCap.Butt, effect))
    }

    private fun DrawScope.hairline(r: Rect, color: Color) {
        drawRect(outlineColor, r.topLeft, r.size, style = strokeOf(3 * d))
        drawRect(color, r.topLeft, r.size, style = strokeOf(1 * d))
    }

    private fun DrawScope.brackets(r: Rect, color: Color, effect: PathEffect?) {
        val a = HudGeometry.bracketArm(r.width, r.height, AdosHudTarget.BracketRatio, AdosHudTarget.BracketMin.value * d, AdosHudTarget.BracketMax.value * d)
        path.rewind()
        path.moveTo(r.left, r.top + a); path.lineTo(r.left, r.top); path.lineTo(r.left + a, r.top)
        path.moveTo(r.right - a, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + a)
        path.moveTo(r.right, r.bottom - a); path.lineTo(r.right, r.bottom); path.lineTo(r.right - a, r.bottom)
        path.moveTo(r.left + a, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - a)
        outlined(path, color, stroke, effect)
    }

    private fun DrawScope.reticle(r: Rect) {
        val gap = AdosHudTarget.ReticleGap.value * d
        val arm = AdosHudTarget.ReticleArm.value * d
        if (r.width < 2 * (gap + arm) || r.height < 2 * (gap + arm)) return
        val cx = r.center.x
        val cy = r.center.y
        path.rewind()
        path.moveTo(cx, cy - gap - arm); path.lineTo(cx, cy - gap)
        path.moveTo(cx, cy + gap); path.lineTo(cx, cy + gap + arm)
        path.moveTo(cx - gap - arm, cy); path.lineTo(cx - gap, cy)
        path.moveTo(cx + gap, cy); path.lineTo(cx + gap + arm, cy)
        outlined(path, c.hudInk, 2 * d, null)
    }

    private fun DrawScope.feetTick(r: Rect) {
        path.rewind()
        path.moveTo(r.center.x - 5 * d, r.bottom)
        path.lineTo(r.center.x + 5 * d, r.bottom)
        outlined(path, c.hudTrack, stroke, null)
    }

    private fun DrawScope.ghost(item: DrawItem) {
        if (item.ghost.size < 2) return
        path.rewind()
        item.ghost.forEachIndexed { i, (x, y) ->
            val px = view.left + x * scale
            val py = view.top + y * scale
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, c.hudCoast.copy(alpha = 0.6f), style = strokeOf(1.5f * d, StrokeCap.Round, ghostDash))
    }

    private fun DrawScope.searchRing(item: DrawItem, r: Rect) {
        val radius = max(24 * d, 0.6f * r.height)
        val center = Offset(r.center.x, r.bottom)
        val effect = if (motion.reducedMotion) coastDash else null
        drawCircle(outlineColor, radius, center, style = strokeOf(1.5f * d + 2 * outline, StrokeCap.Butt, effect))
        drawCircle(c.hudCoast, radius, center, style = strokeOf(1.5f * d, StrokeCap.Butt, effect))
        if (!motion.reducedMotion) {
            val sweep = (now % SWEEP_MS).toFloat() / SWEEP_MS * 360f
            drawArc(c.hudCoast, sweep - 90f, 90f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = strokeOf(4 * d))
        }
        // The SEARCHING tag sits above the ring rather than the predicted box.
        item.top?.let { plate(tagLayout(it), Offset(center.x - radius, center.y - radius - 20 * d), c.hudGlass, c.hudCoast) }
    }

    private fun DrawScope.drawOffFrame(item: DrawItem) {
        val rel = item.relBearingDeg ?: return
        val (ax, ay) = HudGeometry.offFrameAnchor(rel, view.width, view.height, 16 * d)
        val tip = Offset(view.left + ax, view.top + ay)
        rotate(rel, tip) {
            path.rewind()
            path.moveTo(tip.x, tip.y); path.lineTo(tip.x - 7 * d, tip.y + 14 * d); path.lineTo(tip.x + 7 * d, tip.y + 14 * d); path.close()
            outlined(path, c.hudCoast, 1.5f * d, null)
        }
        // The tag sits inward of the arrow, toward the centre.
        val m = tagLayout(item.top ?: return)
        val inward = Offset(view.center.x - tip.x, view.center.y - tip.y).let { o -> val len = max(1f, o.getDistance()); Offset(o.x / len, o.y / len) }
        val at = tip + inward * (28 * d) - Offset(m.size.width / 2f, m.size.height / 2f)
        plate(m, clampInto(at, m.size.width.toFloat(), m.size.height.toFloat()), c.hudGlass, c.hudCoast)
    }

    private fun DrawScope.topTag(item: DrawItem, r: Rect, pip: Boolean, compact: Boolean, placed: MutableList<Rect>, alpha: Float) {
        if (item.kind == TargetKind.Searching) return
        val tag = item.top ?: return
        if (item.kind == TargetKind.Candidate && (pip || compact || r.height < 24 * d)) return
        val m = if (compact && tag is TargetTag.Tracked) compactLayout(tag) else tagLayout(tag)
        val w = m.size.width + 2 * pad()
        val h = m.size.height + pad()
        var y = r.top - 3 * d - h
        if (y < view.top) y = r.top + 2 * d
        val x = r.left.coerceIn(view.left, max(view.left, view.right - w))
        val box = Rect(x, y, x + w, y + h)
        for (i in placed.indices) if (placed[i].overlaps(box)) return
        placed += box
        val fill = tagFill(tag)
        val ink = tagInk(item.kind, tag)
        plate(m, Offset(x, y), fill.copy(alpha = fill.alpha * alpha), ink.copy(alpha = ink.alpha * alpha))
    }

    private fun bottomInk(item: DrawItem, t: TargetTag): Color = when {
        t is TargetTag.FollowStale -> c.hudWarn
        t is TargetTag.Sigma -> c.hudMuted
        item.kind == TargetKind.Coasting -> c.hudCoast
        else -> c.hudInk
    }

    private fun DrawScope.bottomTags(item: DrawItem, r: Rect) {
        val bottom = item.bottom
        if (bottom.isEmpty()) return
        val gap = 4 * d
        var total = gap * (bottom.size - 1)
        var tallest = 0
        for (i in bottom.indices) {
            val m = tagLayout(bottom[i])
            total += m.size.width + 2 * pad()
            tallest = max(tallest, m.size.height)
        }
        val h = tallest + pad()
        var y = r.bottom + 3 * d
        if (y + h > view.bottom) y = r.bottom - h - 2 * d
        var x = (r.center.x - total / 2).coerceIn(view.left, max(view.left, view.right - total))
        for (i in bottom.indices) {
            val t = bottom[i]
            val m = tagLayout(t)
            plate(m, Offset(x, y), c.hudGlass, bottomInk(item, t))
            x += m.size.width + 2 * pad() + gap
        }
    }

    private fun tagFill(tag: TargetTag): Color = when (tag) {
        is TargetTag.Track -> c.hudTrack
        is TargetTag.Hold -> c.hudWarn.copy(alpha = 0.85f)
        else -> c.hudGlass
    }

    private fun tagInk(kind: TargetKind, tag: TargetTag): Color = when {
        tag is TargetTag.Track || tag is TargetTag.Hold -> Color.Black
        kind == TargetKind.Candidate -> c.hudInk
        kind == TargetKind.Tracked -> c.hudPrimary
        kind == TargetKind.Coasting -> c.hudCoast
        kind == TargetKind.Lost -> c.hudCrit
        else -> c.hudTrack
    }

    private fun pad() = AdosHudTarget.TagPad.value * d

    private fun clampInto(o: Offset, w: Float, h: Float) =
        Offset(o.x.coerceIn(view.left, max(view.left, view.right - w)), o.y.coerceIn(view.top, max(view.top, view.bottom - h)))

    private fun DrawScope.plate(m: TextLayoutResult, at0: Offset, fill: Color, ink: Color) {
        val at = clearOfTopBar(at0, topClear)
        val w = m.size.width + 2 * pad()
        val h = m.size.height + pad()
        drawRoundRect(fill, at, Size(w, h), CornerRadius(4 * d))
        drawText(m, color = ink, topLeft = Offset(at.x + pad(), at.y + pad() / 2))
    }

    private companion object {
        const val SWEEP_MS = 1_200L
        const val MAX_STROKES = 24
        const val MAX_TAGS = 64
    }
}

/** A tag's top-left moved down below [topClear] (the top bar's bottom edge, canvas px) when it would draw under it. */
internal fun clearOfTopBar(at: Offset, topClear: Float): Offset = if (at.y < topClear) Offset(at.x, topClear) else at
