package com.altnautica.gcs.core.skills.tips

import com.altnautica.gcs.core.skills.spec.TipSpec
import com.altnautica.gcs.core.skills.spec.TipsSpec

/** What a device remembers: the tips dismissed for good and how often the flight screen has opened. */
data class TipsState(val seen: List<String> = emptyList(), val openCount: Int = 0)

/** What is on screen now. */
data class TipsContext(
    /**
     * Null while unknown: possibly airborne, so no tip shows and a shown tip hides without
     * being marked seen (a null fact never meets a `show_only_when` condition).
     */
    val armed: Boolean?,
    val airborne: Boolean?,
    val flightScreen: Boolean,
    val sheetOrMenuOpen: Boolean,
    val tourVisible: Boolean,
    val skillsAvailable: Boolean,
    val gotoOffered: Boolean,
    val gimbalAvailable: Boolean,
    /** Anchor id to whether it is laid out on screen now. */
    val anchorsVisible: Map<String, Boolean>,
    val screenOpenedAtMs: Long,
    /** When the last tip of this screen open was hidden, or null. */
    val lastDismissedAtMs: Long?,
    /** The tip on screen now, and since when. */
    val shownTipId: String?,
    val shownAtMs: Long?,
    /** Tips hidden on this open without being seen: not offered again until the next open. */
    val hiddenThisOpen: Set<String> = emptySet(),
    /** An alert banner is on screen: tips wait until it is gone. */
    val alertBannerVisible: Boolean = false,
)

sealed interface TipDecision {
    data object None : TipDecision
    data class Wait(val tipId: String, val atMs: Long) : TipDecision
    data class Show(val tipId: String, val textKey: String, val anchor: String) : TipDecision
    data class Hide(val tipId: String, val markSeen: Boolean, val reason: HideReason) : TipDecision
}

enum class HideReason(val wire: String) { AutoDismiss("auto_dismiss"), Arm("arm"), NotAllowed("not_allowed") }

data class TipRect(val x: Float, val y: Float, val w: Float, val h: Float)

enum class TipSide(val wire: String) { Top("top"), Bottom("bottom"), Left("left"), Right("right") }

data class TipPlacement(val x: Float, val y: Float, val side: TipSide)

/**
 * The contextual tip queue of the cockpit spec's `tips.json`, a port of the shared web
 * module `hud/tips.ts` held to the same golden cases. Pure: the surface keeps [TipsState]
 * per device, reports what is on screen in [TipsContext] and renders the decision.
 */
class TipsEngine(spec: TipsSpec) {
    private val rules = spec.rules

    /** The tips in queue order. */
    val tips: List<TipSpec> = spec.tips.sortedBy { it.order }

    /** Never-cover zone ids (`stop`, `left_column`, `flight_state_chip`). */
    val neverCoverZones: List<String> = rules.neverCoverZones

    private fun fact(ctx: TipsContext, name: String): Boolean? = when (name) {
        "armed" -> ctx.armed
        "airborne" -> ctx.airborne
        "flight_screen" -> ctx.flightScreen
        "sheet_or_menu_open" -> ctx.sheetOrMenuOpen
        "tour_visible" -> ctx.tourVisible
        "alert_banner_visible" -> ctx.alertBannerVisible
        else -> null
    }

    /** Every `show_only_when` condition holds; a condition this surface does not know never holds. */
    private fun allowed(ctx: TipsContext): Boolean = rules.showOnlyWhen.all { (k, want) -> fact(ctx, k) == want }

    private fun triggered(trigger: String, state: TipsState, ctx: TipsContext): Boolean = when (trigger) {
        "flight_screen_first_open" -> ctx.flightScreen
        "flight_screen_second_open" -> state.openCount >= 2
        "skills_available" -> ctx.skillsAvailable
        "goto_offered" -> ctx.gotoOffered
        "gimbal_available" -> ctx.gimbalAvailable
        else -> false
    }

    private fun show(t: TipSpec) = TipDecision.Show(t.id, "tips.${t.id}", t.anchor)

    /** What to do with tips now. */
    fun nextTip(state: TipsState, ctx: TipsContext, nowMs: Long): TipDecision {
        val ok = allowed(ctx)
        val shown = ctx.shownTipId
        if (shown != null) {
            val tip = tips.firstOrNull { it.id == shown }
            // Arming dismisses a tip for good.
            if (ctx.armed == true) return TipDecision.Hide(shown, markSeen = true, reason = HideReason.Arm)
            if (!ok || tip == null || ctx.anchorsVisible[tip.anchor] != true) {
                return TipDecision.Hide(shown, markSeen = false, reason = HideReason.NotAllowed)
            }
            if (ctx.shownAtMs != null && nowMs - ctx.shownAtMs >= rules.autoDismissMs) {
                return TipDecision.Hide(shown, markSeen = false, reason = HideReason.AutoDismiss)
            }
            return show(tip)
        }
        if (!ok) return TipDecision.None
        val next = tips.firstOrNull {
            it.id !in state.seen && it.id !in ctx.hiddenThisOpen && triggered(it.trigger, state, ctx) && ctx.anchorsVisible[it.anchor] == true
        } ?: return TipDecision.None
        val firstAt = ctx.screenOpenedAtMs + rules.firstDelayMs
        val atMs = ctx.lastDismissedAtMs?.let { maxOf(firstAt, it + rules.gapBetweenMs) } ?: firstAt
        return if (nowMs < atMs) TipDecision.Wait(next.id, atMs) else show(next)
    }

    companion object {
        /** Space between the anchor and the tip. */
        const val TIP_GAP: Float = 8f

        /** The state after a tip is dismissed for good. */
        fun dismissTip(state: TipsState, id: String): TipsState = if (id in state.seen) state else state.copy(seen = state.seen + id)

        /** The state after the flight screen opens. */
        fun onFlightScreenOpen(state: TipsState): TipsState = state.copy(openCount = state.openCount + 1)

        /** The state of a device that has seen no tip. */
        fun resetTips(): TipsState = TipsState()

        /**
         * Whether [d] puts its tip in `hiddenThisOpen`: only an unseen auto-dismissal does.
         * Arming marks the tip seen, and a tip hidden because it stopped being allowed (a
         * banner, a sheet, the tour, its anchor gone) goes back to the head of the queue.
         */
        fun hiddenThisOpenAfter(d: TipDecision): Boolean = d is TipDecision.Hide && d.reason == HideReason.AutoDismiss

        private fun intersects(a: TipRect, b: TipRect) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

        private fun contains(outer: TipRect, inner: TipRect) =
            inner.x >= outer.x && inner.y >= outer.y && inner.x + inner.w <= outer.x + outer.w && inner.y + inner.h <= outer.y + outer.h

        /**
         * Spots along one axis for a tip of length [len] in [lo]..[hi]: the centred spot first,
         * then each spot just clear of an avoided span in [edges], nearest the centre first.
         */
        private fun slideSpots(centre: Float, lo: Float, hi: Float, len: Float, edges: List<Pair<Float, Float>>): List<Float> {
            val spots = mutableListOf(centre, lo, hi)
            for ((a, b) in edges) spots += listOf(a - TIP_GAP - len, b + TIP_GAP)
            // min(hi, max(lo, s)) as the web module: no throw when hi < lo.
            return spots.map { minOf(hi, maxOf(lo, it)) }.sortedBy { kotlin.math.abs(it - centre) }
        }

        /**
         * Where a tip of [tipW]×[tipH] goes beside [anchor]: on the side with the most free space
         * inside [viewport], slid along that side so it covers none of [avoid] (the never-cover
         * zones, the top bar among them). An avoided rect that contains the anchor (the top bar,
         * for an anchor in the bar) is excepted for that anchor, with every avoided rect inside
         * it, and the tip goes below it, pushed further down past any avoided rect under it (the
         * alert stack). Null when nothing fits.
         */
        fun placeTip(anchor: TipRect, tipW: Float, tipH: Float, viewport: TipRect, avoid: List<TipRect>): TipPlacement? {
            val right = viewport.x + viewport.w
            val bottom = viewport.y + viewport.h
            val container = avoid
                .filter { contains(it, anchor) && (it.w > anchor.w || it.h > anchor.h) }
                .maxByOrNull { it.w * it.h }
            if (container != null) return placeBelow(anchor, tipW, tipH, viewport, container, avoid.filter { !contains(container, it) })
            val sides = listOf(
                TipSide.Top to anchor.y - viewport.y,
                TipSide.Bottom to bottom - (anchor.y + anchor.h),
                TipSide.Left to anchor.x - viewport.x,
                TipSide.Right to right - (anchor.x + anchor.w),
            ).sortedByDescending { it.second }
            for ((side, free) in sides) {
                val vertical = side == TipSide.Top || side == TipSide.Bottom
                if (free < (if (vertical) tipH else tipW) + TIP_GAP) continue
                val fixed = when (side) {
                    TipSide.Top -> anchor.y - TIP_GAP - tipH
                    TipSide.Bottom -> anchor.y + anchor.h + TIP_GAP
                    TipSide.Left -> anchor.x - TIP_GAP - tipW
                    TipSide.Right -> anchor.x + anchor.w + TIP_GAP
                }
                val lo = if (vertical) viewport.x else viewport.y
                val hi = if (vertical) right - tipW else bottom - tipH
                if (hi < lo) continue
                val centre = if (vertical) anchor.x + anchor.w / 2 - tipW / 2 else anchor.y + anchor.h / 2 - tipH / 2
                val edges = avoid.map { if (vertical) it.x to it.x + it.w else it.y to it.y + it.h }
                for (s in slideSpots(centre, lo, hi, if (vertical) tipW else tipH, edges)) {
                    val rect = if (vertical) TipRect(s, fixed, tipW, tipH) else TipRect(fixed, s, tipW, tipH)
                    if (avoid.none { intersects(rect, it) }) return TipPlacement(rect.x, rect.y, side)
                }
            }
            return null
        }

        /** Below [container] (it holds the anchor) or below an avoided rect under it: the first clear spot from the top. */
        private fun placeBelow(anchor: TipRect, tipW: Float, tipH: Float, viewport: TipRect, container: TipRect, avoid: List<TipRect>): TipPlacement? {
            val bottom = viewport.y + viewport.h
            val centre = anchor.x + anchor.w / 2 - tipW / 2
            val xs = slideSpots(centre, viewport.x, viewport.x + viewport.w - tipW, tipW, avoid.map { it.x to it.x + it.w })
            val below = container.y + container.h + TIP_GAP
            val ys = (listOf(below) + avoid.map { it.y + it.h + TIP_GAP }).filter { it >= below && it + tipH <= bottom }.sorted()
            for (y in ys) {
                for (x in xs) {
                    val rect = TipRect(x, y, tipW, tipH)
                    if (avoid.none { intersects(rect, it) }) return TipPlacement(x, y, TipSide.Bottom)
                }
            }
            return null
        }
    }
}
