package com.altnautica.gcs.core.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Basemap drawn under the overlays: an online provider or an imported raster PMTiles pack. */
sealed interface MapBasemap {
    data class Online(val provider: MapProvider) : MapBasemap

    data class Pack(val pack: PmtilesPack) : MapBasemap
}

/** Overlay colours as ARGB ints, resolved from the active theme. */
data class MapPalette(
    val background: Int,
    val missionPath: Int,
    val trail: Int,
    val track: Int,
    val surveyPreview: Int,
    val fenceInclusion: Int,
    val fenceExclusion: Int,
    val survey: Int,
    val draft: Int,
)

/** MapLibre style documents for the basemaps, plus the overlay sources and layers. */
object MapStyle {
    const val BASEMAP_SOURCE = "basemap"
    const val AREAS_SOURCE = "ados-areas"
    const val LINES_SOURCE = "ados-lines"
    const val POINTS_SOURCE = "ados-points"
    const val POINTS_LAYER = "ados-points"

    /** Raster tile size of every supported provider, in pixels. */
    const val TILE_SIZE = 256

    /** Style with only the basemap; the document offline packs are created from. */
    fun baseStyleJson(provider: MapProvider): String =
        style(MapBasemap.Online(provider), palette = null).toString()

    /** Full style: basemap plus the empty overlay sources the map fills at runtime. */
    fun styleJson(basemap: MapBasemap, palette: MapPalette): String = style(basemap, palette).toString()

    private fun style(basemap: MapBasemap, palette: MapPalette?): JsonObject = buildJsonObject {
        put("version", 8)
        put("name", styleName(basemap))
        putJsonObject("sources") {
            put(BASEMAP_SOURCE, basemapSource(basemap))
            if (palette != null) {
                put(AREAS_SOURCE, emptyGeoJsonSource())
                put(LINES_SOURCE, emptyGeoJsonSource())
                put(POINTS_SOURCE, emptyGeoJsonSource())
            }
        }
        putJsonArray("layers") {
            add(
                buildJsonObject {
                    put("id", "background")
                    put("type", "background")
                    putJsonObject("paint") { put("background-color", cssColor(palette?.background ?: 0xFF000000.toInt())) }
                },
            )
            add(
                buildJsonObject {
                    put("id", BASEMAP_SOURCE)
                    put("type", "raster")
                    put("source", BASEMAP_SOURCE)
                },
            )
            if (palette != null) overlayLayers().forEach { add(it) }
        }
    }

    private fun styleName(basemap: MapBasemap) = when (basemap) {
        is MapBasemap.Online -> "ados-${basemap.provider.id}"
        is MapBasemap.Pack -> "ados-pack"
    }

    private fun basemapSource(basemap: MapBasemap): JsonObject = buildJsonObject {
        put("type", "raster")
        put("tileSize", TILE_SIZE)
        when (basemap) {
            is MapBasemap.Online -> {
                putJsonArray("tiles") { basemap.provider.tileUrls.forEach { add(it) } }
                put("maxzoom", basemap.provider.maxZoom)
                put("attribution", basemap.provider.attributionHtml)
            }
            is MapBasemap.Pack -> put("url", basemap.pack.sourceUrl)
        }
    }

    private fun emptyGeoJsonSource(): JsonObject = buildJsonObject {
        put("type", "geojson")
        putJsonObject("data") {
            put("type", "FeatureCollection")
            putJsonArray("features") {}
        }
    }

    private fun get(property: String): JsonArray = buildJsonArray {
        add("get")
        add(property)
    }

    private fun overlayLayers(): List<JsonObject> = listOf(
        buildJsonObject {
            put("id", "ados-areas-fill")
            put("type", "fill")
            put("source", AREAS_SOURCE)
            putJsonObject("paint") {
                put("fill-color", get("color"))
                put("fill-opacity", get("fillOpacity"))
            }
        },
        buildJsonObject {
            put("id", "ados-areas-outline")
            put("type", "line")
            put("source", AREAS_SOURCE)
            putJsonObject("paint") {
                put("line-color", get("color"))
                put("line-width", 2)
            }
        },
        lineLayer("ados-lines-solid", dashed = false),
        lineLayer("ados-lines-dashed", dashed = true),
        buildJsonObject {
            put("id", POINTS_LAYER)
            put("type", "symbol")
            put("source", POINTS_SOURCE)
            putJsonObject("layout") {
                put("icon-image", get("icon"))
                put("icon-rotate", get("rotation"))
                put("icon-rotation-alignment", "map")
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", true)
                put("symbol-sort-key", get("order"))
            }
        },
    )

    private fun lineLayer(id: String, dashed: Boolean) = buildJsonObject {
        put("id", id)
        put("type", "line")
        put("source", LINES_SOURCE)
        putJsonArray("filter") {
            add("==")
            add(get("dashed"))
            add(dashed)
        }
        putJsonObject("layout") {
            put("line-cap", if (dashed) "butt" else "round")
            put("line-join", "round")
        }
        putJsonObject("paint") {
            put("line-color", get("color"))
            put("line-width", get("width"))
            if (dashed) {
                putJsonArray("line-dasharray") {
                    add(2)
                    add(2)
                }
            }
        }
    }
}

/** CSS colour string MapLibre accepts for an ARGB int. */
internal fun cssColor(argb: Int): String {
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val alpha = (a / 255.0 * 1000).toInt() / 1000.0
    return "rgba($r,$g,$b,$alpha)"
}

/** GeoJSON documents for the overlay sources. */
internal object MapGeoJson {

    fun areas(overlays: MapOverlays, palette: MapPalette): String = featureCollection {
        overlays.polygons.filter { it.points.size >= 3 }.forEach { polygon ->
            addFeature(polygonGeometry(polygon.points + polygon.points.first()), areaProps(polygon.kind, palette))
        }
        overlays.circles.forEach { circle ->
            addFeature(polygonGeometry(GeoMath.circleRing(circle.center, circle.radiusM)), areaProps(circle.kind, palette))
        }
    }

    fun lines(trail: List<GeoPoint>, overlays: MapOverlays, palette: MapPalette): String = featureCollection {
        val all = if (trail.size >= 2) overlays.lines + MapLine("trail", trail, LineKind.Trail) else overlays.lines
        all.filter { it.points.size >= 2 }.forEach { line ->
            val (color, width, dashed) = when (line.kind) {
                LineKind.MissionPath -> Triple(palette.missionPath, 3, false)
                LineKind.Trail -> Triple(palette.trail, 2, false)
                LineKind.Track -> Triple(palette.track, 3, false)
                LineKind.SurveyPreview -> Triple(palette.surveyPreview, 2, true)
            }
            addFeature(
                lineGeometry(line.points),
                buildJsonObject {
                    put("color", cssColor(color))
                    put("width", width)
                    put("dashed", dashed)
                },
            )
        }
    }

    fun points(vehicle: MapVehicle?, home: GeoPoint?, overlays: MapOverlays, iconIds: MapIconIds): String = featureCollection {
        overlays.handles.forEach { handle ->
            addFeature(pointGeometry(handle.position), pointProps(MapIconIds.HANDLE, 0f, 1))
        }
        overlays.rally.forEach { rally ->
            addFeature(pointGeometry(rally.position), pointProps(iconIds.rally(rally.selected), 0f, 2))
        }
        if (home != null) addFeature(pointGeometry(home), pointProps(MapIconIds.HOME, 0f, 3))
        overlays.waypoints.forEach { wp ->
            addFeature(pointGeometry(wp.position), pointProps(iconIds.waypoint(wp), 0f, if (wp.selected) 5 else 4))
        }
        if (vehicle != null) {
            val icon = iconIds.vehicle(vehicle)
            addFeature(pointGeometry(vehicle.position), pointProps(icon, vehicle.headingDeg ?: 0f, 6))
        }
    }

    private fun areaProps(kind: AreaKind, palette: MapPalette) = buildJsonObject {
        val color = when (kind) {
            AreaKind.FenceInclusion -> palette.fenceInclusion
            AreaKind.FenceExclusion -> palette.fenceExclusion
            AreaKind.Survey -> palette.survey
            AreaKind.Draft -> palette.draft
        }
        put("color", cssColor(color))
        put("fillOpacity", if (kind == AreaKind.FenceExclusion) 0.25 else 0.12)
    }

    private fun pointProps(icon: String, rotation: Float, order: Int) = buildJsonObject {
        put("icon", icon)
        put("rotation", rotation)
        put("order", order)
    }

    private class Collection {
        val features = mutableListOf<JsonObject>()

        fun addFeature(geometry: JsonObject, properties: JsonObject) {
            features += buildJsonObject {
                put("type", "Feature")
                put("geometry", geometry)
                put("properties", properties)
            }
        }
    }

    private fun featureCollection(block: Collection.() -> Unit): String {
        val collection = Collection().apply(block)
        return buildJsonObject {
            put("type", "FeatureCollection")
            put("features", JsonArray(collection.features))
        }.toString()
    }

    private fun position(p: GeoPoint) = buildJsonArray {
        add(JsonPrimitive(p.lonDeg))
        add(JsonPrimitive(p.latDeg))
    }

    private fun pointGeometry(p: GeoPoint) = buildJsonObject {
        put("type", "Point")
        put("coordinates", position(p))
    }

    private fun lineGeometry(points: List<GeoPoint>) = buildJsonObject {
        put("type", "LineString")
        put("coordinates", JsonArray(points.map { position(it) }))
    }

    private fun polygonGeometry(ring: List<GeoPoint>) = buildJsonObject {
        put("type", "Polygon")
        putJsonArray("coordinates") { addJsonArray { ring.forEach { add(position(it)) } } }
    }
}
