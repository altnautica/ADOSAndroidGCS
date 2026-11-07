package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.skills.controls.AxisCalibration
import com.altnautica.gcs.core.skills.controls.PadCalibration
import com.altnautica.gcs.core.skills.input.GamepadAxes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Records a stick calibration from raw samples. First both sticks visit every edge
 * (each axis past [EDGE] of full travel both ways); then, released and still for
 * [STILL_MS], the resting position is the centre. The extents are the furthest
 * travel seen on each axis.
 */
internal class CalibrationRecorder {
    private val lo = FloatArray(AXES) { Float.MAX_VALUE }
    private val hi = FloatArray(AXES) { -Float.MAX_VALUE }
    private val rest = FloatArray(AXES)
    private var stillSince = -1L

    var phase: CalibrationPhase = CalibrationPhase.Moving
        private set

    /** Share of the eight edges (four axes, both ways) reached so far. */
    val coverage: Float
        get() {
            var reached = 0
            for (i in 0 until AXES) {
                if (lo[i] <= -EDGE) reached++
                if (hi[i] >= EDGE) reached++
            }
            return reached / (AXES * 2f)
        }

    /** Adds one sample taken at [nowMs]; returns the calibration once the release settles, else null. */
    fun add(raw: GamepadAxes, nowMs: Long): PadCalibration? {
        val v = floatArrayOf(raw.leftX, raw.leftY, raw.rightX, raw.rightY)
        for (i in 0 until AXES) {
            lo[i] = min(lo[i], v[i])
            hi[i] = max(hi[i], v[i])
        }
        if (phase == CalibrationPhase.Moving && coverage >= 1f) phase = CalibrationPhase.Release
        if (phase != CalibrationPhase.Release) return null
        val released = v.all { abs(it) < RELEASED }
        val still = released && stillSince >= 0 && (0 until AXES).all { abs(v[it] - rest[it]) < STILL_DELTA }
        if (!still) {
            v.copyInto(rest)
            stillSince = if (released) nowMs else -1L
            return null
        }
        if (nowMs - stillSince < STILL_MS) return null
        phase = CalibrationPhase.Saved
        return PadCalibration(
            lx = AxisCalibration(rest[0], lo[0], hi[0]),
            ly = AxisCalibration(rest[1], lo[1], hi[1]),
            rx = AxisCalibration(rest[2], lo[2], hi[2]),
            ry = AxisCalibration(rest[3], lo[3], hi[3]),
        )
    }

    private companion object {
        const val AXES = 4

        /** Travel that counts as reaching an edge. */
        const val EDGE = 0.85f

        /** A released stick rests within this of the middle. */
        const val RELEASED = 0.3f

        /** Drift allowed while the release settles. */
        const val STILL_DELTA = 0.02f

        const val STILL_MS = 600L
    }
}
