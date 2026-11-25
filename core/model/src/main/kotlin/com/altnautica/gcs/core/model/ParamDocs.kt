package com.altnautica.gcs.core.model

/** What a flight-controller parameter is, for surfaces outside the parameter editor. */
data class ParamDoc(
    val name: String,
    /** The firmware's short human name, e.g. "Ground Station Failsafe Enable". */
    val title: String,
    /** The firmware's description sentence. */
    val description: String,
    /** Display units ("s", "m"); null when the parameter has none. */
    val units: String?,
    /** Enumerated value labels. */
    val options: Map<Double, String>,
    /** Bitmask bit labels. */
    val bits: Map<Int, String>,
    val rebootRequired: Boolean,
)

/** Parameter documentation of one flight controller, by parameter name. */
fun interface ParamDocBook {
    fun doc(name: String): ParamDoc?
}

/** Finds the bundled parameter documentation for a flight controller. */
interface ParamDocs {
    /** The documentation for the flight controller behind this heartbeat; null when none is bundled (MSP, unknown). */
    suspend fun book(autopilot: Int, vehicleType: Int): ParamDocBook?
}
