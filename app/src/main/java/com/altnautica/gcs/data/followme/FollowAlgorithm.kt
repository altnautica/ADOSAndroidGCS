package com.altnautica.gcs.data.followme

/**
 * Follow-me algorithm variants. Each defines how the drone tracks the phone's
 * GPS position relative to the operator.
 */
sealed class FollowAlgorithm(val name: String) {
    /** Drone holds position until operator moves beyond [radiusM], then follows. */
    data class Leash(val radiusM: Float = 10f) : FollowAlgorithm("Leash")

    /** Drone flies ahead of the operator by [leadDistanceM] in the direction of travel. */
    data class Lead(val leadDistanceM: Float = 5f) : FollowAlgorithm("Lead")

    /** Drone orbits the operator at [radiusM] with tangential [speedMs]. */
    data class Orbit(val radiusM: Float = 15f, val speedMs: Float = 2f) : FollowAlgorithm("Orbit")

    /** Drone stays directly above the operator at a fixed altitude offset. */
    data object Above : FollowAlgorithm("Above")
}

/**
 * Computed target position to send to the flight controller via GUIDED mode.
 */
data class GuidedTarget(
    val lat: Double,
    val lon: Double,
    val alt: Float,
)
