package com.altnautica.gcs.feature.cockpit.autonomy

import kotlin.math.cos
import kotlin.math.sin

/** Radar scale choice: a pinned range or automatic. */
enum class RadarScale(val rangeM: Float?) { Three(3f), Six(6f), Twelve(12f), Auto(null) }

/** Lidar radar and local-frame minimap maths (HUD v2 §9, §10). */
object LocalFrame {
    const val SCAN_STALE_MS = 500L
    const val POSE_STALE_MS = 500L
    const val DANGER_M = 2f
    const val CAUTION_M = 5f

    /** Next scale when the dial is tapped: 3 → 6 → 12 → auto → 3. */
    fun nextScale(s: RadarScale): RadarScale = when (s) {
        RadarScale.Three -> RadarScale.Six
        RadarScale.Six -> RadarScale.Twelve
        RadarScale.Twelve -> RadarScale.Auto
        RadarScale.Auto -> RadarScale.Three
    }

    /** Display range: the pinned scale, else 3 m when anything is within 1.5 m, 12 m when nothing within 6 m, else 6 m. */
    fun displayRange(scale: RadarScale, nearestM: Float?): Float = scale.rangeM ?: when {
        nearestM != null && nearestM < 1.5f -> 3f
        nearestM == null || nearestM > 6f -> 12f
        else -> 6f
    }

    /**
     * Scan dots in dial pixels around ([cx], [cy]) at radius [radiusPx] for [displayRangeM];
     * returns x0, y0, x1, y1… at most [maxDots] points (evenly decimated).
     */
    fun scanDots(scan: ScanFrame, displayRangeM: Float, cx: Float, cy: Float, radiusPx: Float, maxDots: Int): FloatArray {
        val idx = scan.rangesM.indices.filter { val r = scan.rangesM[it]; r > 0f && r <= displayRangeM }
        val step = if (idx.size > maxDots) idx.size.toFloat() / maxDots else 1f
        val n = minOf(idx.size, maxDots)
        val out = FloatArray(n * 2)
        for (k in 0 until n) {
            val i = idx[(k * step).toInt()]
            val theta = scan.angleStartRad + i * scan.angleIncrementRad
            val r = scan.rangesM[i] / displayRangeM * radiusPx
            out[k * 2] = cx + r * sin(theta)
            out[k * 2 + 1] = cy - r * cos(theta)
        }
        return out
    }

    /** Nearest positive scan return in metres, or null. */
    fun nearestScanM(scan: ScanFrame): Float? = scan.rangesM.filter { it > 0f }.minOrNull()

    /** Readout word for a nearest distance. */
    fun proximityWord(nearestM: Float): ProximityWord = when {
        nearestM < DANGER_M -> ProximityWord.Danger
        nearestM <= CAUTION_M -> ProximityWord.Caution
        else -> ProximityWord.Clear
    }

    /** Local-frame screen point for map (north [x], east [y]) about centre ([xc], [yc]) at [pxPerM]. */
    fun toScreen(x: Float, y: Float, xc: Float, yc: Float, pxPerM: Float, screenCx: Float, screenCy: Float): Pair<Float, Float> =
        (screenCx + (y - yc) * pxPerM) to (screenCy - (x - xc) * pxPerM)

    /** Tile (ix, iy) top-left anchor in map metres: (north = (ix+1)·cells·res, east = iy·cells·res). */
    fun tileAnchor(ix: Int, iy: Int, resM: Float, cells: Int = 64): Pair<Float, Float> =
        ((ix + 1) * cells * resM) to (iy * cells * resM)

    /** Body FRD point (forward [f], right [r]) to map north/east with pose ([n], [e], [yaw]). */
    fun bodyToMap(f: Float, r: Float, n: Float, e: Float, yaw: Float): Pair<Float, Float> =
        (n + f * cos(yaw) - r * sin(yaw)) to (e + f * sin(yaw) + r * cos(yaw))

    /** Scale-bar step from {1, 2, 5, 10, 20, 50} m so it spans 40-80 px; the largest step under 80 px otherwise. */
    fun scaleBarM(pxPerM: Float): Int {
        val steps = intArrayOf(1, 2, 5, 10, 20, 50)
        return steps.firstOrNull { it * pxPerM in 40f..80f } ?: steps.lastOrNull { it * pxPerM < 80f } ?: 1
    }
}

enum class ProximityWord { Clear, Caution, Danger }
