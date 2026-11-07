package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One data layer of the cockpit spec: its id, default, data source and whether it needs a compute lease. */
@Immutable
data class LayerDef(val id: String, val defaultOn: Boolean, val source: String, val lease: Boolean, val z: Int = 0)

/**
 * The data layers, clutter levels and views of the shared cockpit spec
 * (`layers.json`, `views.json`). Every surface draws the same layers in the same order.
 */
@Immutable
data class LayerSpec(val layers: List<LayerDef>, val routeLevel: Set<String>, val offLevel: Set<String>, val views: Map<String, Set<String>>) {
    /** The layers on at [level]. */
    fun levelSet(level: LayerLevel): Set<String> = when (level) {
        LayerLevel.Full -> layers.filter { it.defaultOn }.mapTo(LinkedHashSet()) { it.id }
        LayerLevel.Route -> routeLevel
        LayerLevel.Off -> offLevel
    }

    fun defaults(): LayerFlags = LayerFlags(LayerLevel.Full, levelSet(LayerLevel.Full))

    /** The layers [view] forces on while it is open. */
    fun forced(view: String): Set<String> = views[view].orEmpty()

    /** The canvas layers (everything but the HUD chrome) in ascending z. */
    val canvasOrder: List<String> get() = layers.filter { it.id != HUD }.map { it.id }

    fun def(id: String): LayerDef? = layers.firstOrNull { it.id == id } ?: if (id.startsWith(PLUGIN_PREFIX)) LayerDef(id, true, "plugin", false) else null

    companion object {
        const val HUD = "hud"
        const val TARGETS = "targets"
        const val AR_ROUTE = "ar_route"
        const val AR_MOTION = "ar_motion"
        const val OBSTACLES = "obstacles"
        const val DEPTH = "depth"
        const val SURFACES = "surfaces"
        const val PLUGIN_PREFIX = "plugin:"

        const val VIEW_PILOT = "pilot"
        const val VIEW_MAP = "map"
        const val VIEW_SPLIT = "split"
        const val VIEW_VISION = "vision"

        /** Parses the spec's `layers.json` and `views.json`; null when either is malformed. */
        fun parse(layersJson: String, viewsJson: String): LayerSpec? = runCatching {
            val json = Json { ignoreUnknownKeys = true }
            val l = json.parseToJsonElement(layersJson).jsonObject
            val defs = l["layers"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                LayerDef(
                    id = o["id"]!!.jsonPrimitive.content,
                    defaultOn = o["default"]!!.jsonPrimitive.boolean,
                    source = o["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    lease = o["lease"]?.jsonPrimitive?.booleanOrNull == true,
                    z = o["z"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }.sortedBy { it.z }
            val levels = l["levels"]!!.jsonObject
            fun ids(level: String) = levels[level]!!.jsonObject["layers"]!!.jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
            val v = json.parseToJsonElement(viewsJson).jsonObject
            val views = v["views"]!!.jsonArray.associate { e ->
                val o = e.jsonObject
                o["id"]!!.jsonPrimitive.content to o["forced_layers"]!!.jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
            }
            LayerSpec(defs, ids("route"), ids("off"), views)
        }.getOrNull()

        /** Reads the spec from the module's `cockpit-spec` assets; the built-in copy if they are unreadable. */
        fun load(assets: android.content.res.AssetManager): LayerSpec = runCatching {
            parse(
                assets.open("cockpit-spec/layers.json").bufferedReader().use { it.readText() },
                assets.open("cockpit-spec/views.json").bufferedReader().use { it.readText() },
            )
        }.getOrNull() ?: DEFAULT

        /** The built-in copy of the cockpit spec's `layers.json` and `views.json`. */
        val DEFAULT = LayerSpec(
            layers = listOf(
                LayerDef(DEPTH, false, "depth", true, 10),
                LayerDef(SURFACES, false, "surfaces", true, 20),
                LayerDef(OBSTACLES, true, "obstacles", false, 30),
                LayerDef(TARGETS, true, "vision detections", false, 40),
                LayerDef(AR_ROUTE, true, "ar", false, 50),
                LayerDef(AR_MOTION, true, "ar", false, 60),
                LayerDef(HUD, true, "telemetry", false, 100),
            ),
            routeLevel = setOf(HUD, TARGETS, AR_ROUTE, AR_MOTION, OBSTACLES),
            offLevel = setOf(HUD),
            views = mapOf(
                VIEW_PILOT to emptySet(),
                VIEW_MAP to emptySet(),
                VIEW_SPLIT to emptySet(),
                VIEW_VISION to setOf(TARGETS, DEPTH, SURFACES),
            ),
        )
    }
}
