package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.runtime.Immutable

/** The data-layer clutter level: Full, Route or Off. */
enum class LayerLevel(val wire: String) {
    Full("full"), Route("route"), Off("off");

    fun cycle(step: Int): LayerLevel = entries[Math.floorMod(ordinal + step, entries.size)]

    companion object {
        fun of(wire: String?): LayerLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The layer set on one surface for one vehicle: the [level] plus every layer's on/off
 * flag. Choosing a level resets the flags to that level's set; toggling one layer
 * afterwards changes only that layer.
 */
@Immutable
data class LayerFlags(val level: LayerLevel, val on: Set<String>) {
    fun isOn(id: String): Boolean = id in on

    /** Flags reset to [level]'s set; leased layers (depth, surfaces) keep their own state. */
    fun withLevel(level: LayerLevel, spec: LayerSpec): LayerFlags {
        val leased = spec.layers.filter { it.lease }.mapTo(HashSet()) { it.id }
        return LayerFlags(level, spec.levelSet(level) - leased + on.filterTo(HashSet()) { it in leased })
    }

    fun toggled(id: String): LayerFlags = copy(on = if (id in on) on - id else on + id)

    /** The flags with [forced] layers on (a view that forces layers while it is open). */
    fun forcing(forced: Set<String>): LayerFlags = if (on.containsAll(forced)) this else copy(on = on + forced)
}
