package com.altnautica.gcs.core.model

/** What a tracked mover is, by its wire name. */
enum class MoverKind(val wire: String) {
    /** A person (a camera track classed `person`): the pilot keeps its separation from them. */
    Person("person"),

    /** A road vehicle, bicycle or other machine (a camera track). */
    Vehicle("vehicle"),

    /** Something the obstacle sensors see move without a camera class: kept clear as a person is. */
    Unknown("unknown");

    companion object {
        /** The kind of [wire]; a kind this build does not know reads as [Unknown]. */
        fun of(wire: String?): MoverKind = entries.firstOrNull { it.wire == wire } ?: Unknown
    }
}

/** Where the pilot learnt of a mover, by its wire name. */
enum class MoverSource(val wire: String) {
    /** A camera tracker's track with a world position. */
    Vision("vision"),

    /** A small cluster of obstacle returns seen moving. */
    Scan("scan");

    companion object {
        /** The source of [wire]; anything but `vision` reads as [Scan]. */
        fun of(wire: String?): MoverSource = if (wire == Vision.wire) Vision else Scan
    }
}

/**
 * One moving obstacle the pilot tracks (`movers`), in the flight controller's local NED frame
 * at the frame's time: [northM] / [eastM] its position (m), [velNorthMps] / [velEastMps] its
 * velocity (m/s), [sigmaM] the one-sigma uncertainty of its position (m) and [ageMs] how long
 * since it was last measured. [id] is the pilot's, stable while it is tracked.
 */
data class TrackedMover(
    val id: Long,
    val kind: MoverKind,
    val source: MoverSource,
    val northM: Float,
    val eastM: Float,
    val velNorthMps: Float,
    val velEastMps: Float,
    val sigmaM: Float,
    val ageMs: Long,
) {
    /** Not measured for over [STALE_MS]: its position is a prediction, drawn faded. */
    val stale: Boolean get() = ageMs > STALE_MS

    companion object {
        /** A mover not measured for longer than this is drawn faded. */
        const val STALE_MS = 1_000L
    }
}
