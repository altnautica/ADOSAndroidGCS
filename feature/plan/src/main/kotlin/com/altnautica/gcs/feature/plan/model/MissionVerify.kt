package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.link.mission.MissionItem
import kotlin.math.abs

/**
 * Compares an uploaded list with the one read back from the vehicle: same commands,
 * frames and parameters; positions within 1e-7 degrees and altitudes within 0.01 m.
 */
object MissionVerify {
    private const val POSITION_UNITS = 1
    private const val ALT_TOLERANCE_M = 0.01f
    private const val PARAM_TOLERANCE = 1e-4f

    fun matches(sent: List<MissionItem>, readBack: List<MissionItem>): Boolean =
        sent.size == readBack.size && sent.indices.all { same(sent[it], readBack[it]) }

    private fun same(a: MissionItem, b: MissionItem): Boolean =
        a.command == b.command &&
            intFrame(a.frame) == intFrame(b.frame) &&
            close(a.param1, b.param1) && close(a.param2, b.param2) &&
            close(a.param3, b.param3) && close(a.param4, b.param4) &&
            abs(a.x - b.x) <= POSITION_UNITS && abs(a.y - b.y) <= POSITION_UNITS &&
            (abs(a.z - b.z) <= ALT_TOLERANCE_M || (a.z.isNaN() && b.z.isNaN()))

    private fun close(a: Float, b: Float) = (a.isNaN() && b.isNaN()) || abs(a - b) <= PARAM_TOLERANCE

    /** A vehicle may echo a global frame as its `_INT` twin; both mean the same reference. */
    private fun intFrame(frame: Int) = when (frame) {
        MavFrame.GLOBAL -> MavFrame.GLOBAL_INT
        MavFrame.GLOBAL_RELATIVE_ALT -> MavFrame.GLOBAL_RELATIVE_ALT_INT
        MavFrame.GLOBAL_TERRAIN_ALT -> MavFrame.GLOBAL_TERRAIN_ALT_INT
        else -> frame
    }
}
