package com.altnautica.gcs.core.model

/**
 * The pilot does not trust the horizontal position: [reason] is `flow_disagrees`,
 * `extnav_lost` or `extnav_degraded`; [detail] Navigation's own reason when it named one;
 * [flowMps] / [estimateMps] the measured flow speed and the estimate's speed when sent.
 */
data class PositionUnverified(val reason: String, val detail: String?, val flowMps: Float?, val estimateMps: Float?)

/**
 * Leaves or a ceiling the pilot sees overhead along the way of lease [leaseId]'s goal: the
 * airframe passes under it up to [flyUnderAglM] m over the ground, [aheadM] m (horizontal)
 * along the way.
 */
data class OverheadAhead(val leaseId: Long, val flyUnderAglM: Float, val aheadM: Float)

/** The world model the pilot sees obstacles with, by its wire name. */
enum class SensingTier(val wire: String) {
    /** A 3D lidar's voxel map. */
    Lidar3d("lidar3d"),

    /** A depth camera's voxel map, known only inside the camera's view. */
    Depth("depth"),

    /** Learned depth from a camera. */
    Camera("camera"),

    /** A planar lidar's scan. */
    Planar("planar");

    companion object {
        fun of(wire: String?): SensingTier? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The pilot's sensing: its [tier], the range it sees clear along its motion ([clearRangeM], m)
 * and the horizontal speed that still stops inside that range ([speedLimitMps], m/s).
 */
data class Sensing(val tier: SensingTier, val clearRangeM: Float, val speedLimitMps: Float)
