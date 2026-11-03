package com.altnautica.gcs.feature.cockpit.autonomy

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Pure HUD v2 geometry and number formatting shared by the overlay, range panel, radar and minimap. */
object HudGeometry {
    /** Bracket arm: `clamp(ratio · min(w, h), min, max)`. */
    fun bracketArm(w: Float, h: Float, ratio: Float = 0.22f, minArm: Float = 10f, maxArm: Float = 28f): Float =
        (ratio * min(w, h)).coerceIn(minArm, maxArm)

    /** One decimal below 100 m, an integer from 100 m: `10.0 m`, `123 m`. */
    fun formatRange(m: Float): String = formatNumber(m) + " m"

    /** The number part of [formatRange]. */
    fun formatNumber(m: Float): String {
        val tenth = (m * 10).roundToInt() / 10f
        return if (tenth >= 100f) m.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", tenth)
    }

    /** `range ± 2σ` as `9.6 – 10.8 m`. */
    fun rangeInterval(rangeM: Float, sigmaM: Float): String =
        "${formatNumber(rangeM - 2 * sigmaM)} – ${formatNumber(rangeM + 2 * sigmaM)} m"

    /** Range-panel scale end: `max(20, ceil5(range + 2σ))`. */
    fun rangeScaleMax(rangeM: Float, sigmaM: Float): Float = maxOf(20f, ceil((rangeM + 2 * sigmaM) / 5f) * 5f)

    /** One decimal, root locale. */
    fun oneDecimal(v: Float): String = String.format(Locale.ROOT, "%.1f", v)

    /** Three-digit bearing in [0, 360). */
    fun bearing3(deg: Float): String = String.format(Locale.ROOT, "%03d", Math.floorMod(deg.roundToInt(), 360))

    /**
     * Where the off-frame arrow sits: the ray from the rect centre at screen angle [relDeg]
     * (clockwise from up) meets the rect inset by [inset]. Returns (x, y) in the rect's space.
     */
    fun offFrameAnchor(relDeg: Float, width: Float, height: Float, inset: Float = 16f): Pair<Float, Float> {
        val cx = width / 2
        val cy = height / 2
        val rad = Math.toRadians(relDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val tx = if (abs(dx) < 1e-6f) Float.MAX_VALUE else (if (dx > 0) (width - inset - cx) else (inset - cx)) / dx
        val ty = if (abs(dy) < 1e-6f) Float.MAX_VALUE else (if (dy > 0) (height - inset - cy) else (inset - cy)) / dy
        val t = min(tx, ty)
        return (cx + dx * t) to (cy + dy * t)
    }

    /** Vertical FOV of a pinhole camera with horizontal FOV [hfovDeg] and a [w]×[h] frame. */
    fun verticalFov(hfovDeg: Float, w: Float, h: Float): Float? {
        if (w <= 0f || h <= 0f) return null
        val half = Math.toRadians(hfovDeg / 2.0)
        return Math.toDegrees(2 * kotlin.math.atan(kotlin.math.tan(half) * h / w)).toFloat()
    }

    /** The letterboxed rect a [frameW]×[frameH] frame occupies inside a [boxW]×[boxH] container: (left, top, scale). */
    fun fitRect(frameW: Float, frameH: Float, boxW: Float, boxH: Float): Triple<Float, Float, Float> {
        if (frameW <= 0 || frameH <= 0) return Triple(0f, 0f, 1f)
        val scale = min(boxW / frameW, boxH / frameH)
        return Triple((boxW - frameW * scale) / 2, (boxH - frameH * scale) / 2, scale)
    }

    /** Hit test: the smallest box (each inflated to at least [minHit] px) containing (x, y); index or -1. */
    fun hitTest(boxes: List<Box>, x: Float, y: Float, minHit: Float): Int {
        var best = -1
        var bestArea = Float.MAX_VALUE
        boxes.forEachIndexed { i, b ->
            val w = maxOf(b.w, minHit)
            val h = maxOf(b.h, minHit)
            val left = b.cx - w / 2
            val top = b.cy - h / 2
            if (x in left..left + w && y in top..top + h && b.w * b.h < bestArea) {
                best = i
                bestArea = b.w * b.h
            }
        }
        return best
    }

    /** First-order smoothing fraction for [dtMs] at time constant [tauMs]; 1 snaps. */
    fun smoothingAlpha(dtMs: Float, tauMs: Float): Float = when {
        tauMs <= 0f -> 1f
        dtMs <= 0f -> 0f
        else -> (1f - kotlin.math.exp(-dtMs / tauMs)).coerceIn(0f, 1f)
    }

    fun easeBox(cur: Box, target: Box, a: Float) = Box(
        cur.x + (target.x - cur.x) * a,
        cur.y + (target.y - cur.y) * a,
        cur.w + (target.w - cur.w) * a,
        cur.h + (target.h - cur.h) * a,
    )
}
