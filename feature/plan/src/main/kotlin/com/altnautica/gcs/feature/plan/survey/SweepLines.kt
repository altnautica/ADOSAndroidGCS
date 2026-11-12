package com.altnautica.gcs.feature.plan.survey

import kotlin.math.ceil
import kotlin.math.max

/** Parallel sweep lines over a polygon in a local metre frame (x, y), shared by survey and spray. */
internal object SweepLines {

    /** Shorter inside spans are dropped: they would be two identical waypoints. */
    private const val MIN_SPAN_M = 0.01

    /**
     * Line offsets across [min]..[max], [spacing] apart and centred: both edge strips are equal
     * and at most half a spacing; a band thinner than one spacing gets one centre line.
     */
    fun offsets(min: Double, max: Double, spacing: Double): List<Double> {
        if (spacing <= 0.0) return emptyList()
        val height = max - min
        val count = max(1, ceil(height / spacing - 1e-9).toInt())
        val first = min + (height - (count - 1) * spacing) / 2.0
        return List(count) { first + it * spacing }
    }

    /**
     * The inside spans of the line at [y] through [polygon]: crossings sorted and paired, so a
     * concave outline (an L or U field) splits the line around its notch instead of crossing it.
     */
    fun spans(polygon: List<DoubleArray>, y: Double): List<Pair<Double, Double>> {
        val xs = ArrayList<Double>()
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if ((a[1] <= y && b[1] > y) || (b[1] <= y && a[1] > y)) {
                xs += a[0] + (y - a[1]) / (b[1] - a[1]) * (b[0] - a[0])
            }
        }
        xs.sort()
        return (0 until xs.size / 2).map { xs[2 * it] to xs[2 * it + 1] }.filter { it.second - it.first >= MIN_SPAN_M }
    }

    /**
     * Boustrophedon passes: every inside span of every line, alternating direction line by
     * line, with the spans of a reversed line visited from its far end.
     */
    fun passes(polygon: List<DoubleArray>, ys: List<Double>): List<Pass> {
        val out = ArrayList<Pass>()
        var line = 0
        for (y in ys) {
            val spans = spans(polygon, y)
            if (spans.isEmpty()) continue
            val forward = line % 2 == 0
            val ordered = if (forward) spans else spans.reversed()
            ordered.forEach { (a, b) -> out += if (forward) Pass(a, b, y) else Pass(b, a, y) }
            line++
        }
        return out
    }

    /** One straight pass from x = [fromX] to x = [toX] along the line at [y]. */
    data class Pass(val fromX: Double, val toX: Double, val y: Double)
}
