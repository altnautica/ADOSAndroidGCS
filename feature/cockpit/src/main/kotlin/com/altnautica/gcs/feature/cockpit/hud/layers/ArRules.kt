package com.altnautica.gcs.feature.cockpit.hud.layers

/** How the mission route draws for the agent's mission state. */
enum class RouteStyle {
    /** `active`: magenta active leg, next gate with ETA, the `after` waypoint. */
    Normal,

    /** `paused`: legs grey and dashed, the next gate hollow. */
    Paused,

    /** `loaded`: white dashed preview. */
    Preview,

    /** `returning`: route hidden, home emphasised. */
    Returning,
}

/** Which AR items draw, per layer flags, clutter level and mission state. Pure, so every rule is unit-tested. */
object ArRules {
    /**
     * The route style for [mission]; null hides the route. A frame without a mission
     * (an agent predating mission state) draws its items as sent. A `loaded` mission
     * previews only while disarmed or while the pilot or the FC (not a skill) flies.
     */
    fun routeStyle(mission: MissionState?, previewAllowed: Boolean): RouteStyle? = when (mission?.state) {
        null, "active" -> RouteStyle.Normal
        "paused" -> RouteStyle.Paused
        "loaded" -> if (previewAllowed) RouteStyle.Preview else null
        "returning" -> RouteStyle.Returning
        else -> null
    }

    /** True when [item] draws. */
    fun include(item: ArItem, flags: LayerFlags, route: RouteStyle?): Boolean {
        val routeShown = route != null && route != RouteStyle.Returning
        val full = flags.level == LayerLevel.Full
        return when (item.t) {
            "wp" -> flags.isOn(LayerSpec.AR_ROUTE) && routeShown && (full || item.role == "next" || item.role == "after")
            "leg" -> flags.isOn(LayerSpec.AR_ROUTE) && routeShown && (full || item.role == "active" || item.role == "next")
            "home" -> flags.isOn(LayerSpec.AR_ROUTE)
            "edge" -> flags.isOn(LayerSpec.AR_ROUTE) && (item.of == "home" || routeShown)
            "fpv", "fpath", "fgoal", "orbit" -> flags.isOn(LayerSpec.AR_MOTION)
            else -> false
        }
    }

    /** `size` → marker px: `24 + 40·size` dp. */
    fun markerDp(size: Float?): Float = 24f + 40f * (size ?: 0.25f).coerceIn(0.25f, 1f)

    /** The next-waypoint chip: `WP4 · 120 m · 0:42`. */
    fun wpChip(seq: Int?, distM: Float?, etaS: Float?): String = buildString {
        append("WP").append(seq ?: "?")
        distM?.let { append(" · ").append(Math.round(it)).append(" m") }
        etaS?.let {
            val s = Math.round(it)
            append(" · ").append(s / 60).append(':').append((s % 60).toString().padStart(2, '0'))
        }
    }
}
