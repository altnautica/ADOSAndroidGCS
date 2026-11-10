package com.altnautica.gcs.feature.cockpit.hud

/** The spec's sentence-case text with its first word in capitals ("Lidar 10 Hz" → "LIDAR 10 Hz"); units keep their case. */
internal fun String.upperFirstWord(): String {
    val space = indexOf(' ')
    return if (space < 0) uppercase() else substring(0, space).uppercase() + substring(space)
}
