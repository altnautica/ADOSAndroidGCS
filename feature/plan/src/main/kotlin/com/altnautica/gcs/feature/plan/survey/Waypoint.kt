package com.altnautica.gcs.feature.plan.survey

/** A navigation waypoint produced by the pattern generators, ready for mission upload. */
data class Waypoint(val lat: Double, val lon: Double, val alt: Float, val speed: Float)
