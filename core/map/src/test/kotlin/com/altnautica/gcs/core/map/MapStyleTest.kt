package com.altnautica.gcs.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleTest {

    private val palette = MapPalette(
        background = 0xFF0A0A0F.toInt(),
        missionPath = 0xFF3A82FF.toInt(),
        trail = 0xFF5B9AFF.toInt(),
        track = 0xFFDFF140.toInt(),
        surveyPreview = 0xFFDFF140.toInt(),
        fenceInclusion = 0xFF22C55E.toInt(),
        fenceExclusion = 0xFFEF4444.toInt(),
        survey = 0xFFDFF140.toInt(),
        draft = 0xFF94A3B8.toInt(),
    )

    @Test
    fun `providers carry the published attribution`() {
        assertEquals("&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>", MapProvider.Osm.attributionHtml)
        assertEquals("&copy; <a href=\"https://www.esri.com/\">Esri</a>", MapProvider.EsriWorldImagery.attributionHtml)
        assertEquals("&copy; <a href=\"https://opentopomap.org\">OpenTopoMap</a>", MapProvider.OpenTopoMap.attributionHtml)
        assertEquals("\u00A9 OpenStreetMap", MapProvider.Osm.attributionText)
    }

    @Test
    fun `subdomain templates expand into one url per subdomain`() {
        assertEquals(
            listOf(
                "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
            ),
            MapProvider.Osm.tileUrls,
        )
        assertEquals(1, MapProvider.EsriWorldImagery.tileUrls.size)
    }

    @Test
    fun `base style holds only the raster basemap`() {
        val style = Json.parseToJsonElement(MapStyle.baseStyleJson(MapProvider.OpenTopoMap)).jsonObject

        val sources = style.getValue("sources").jsonObject
        assertEquals(setOf(MapStyle.BASEMAP_SOURCE), sources.keys)
        val basemap = sources.getValue(MapStyle.BASEMAP_SOURCE).jsonObject
        assertEquals("raster", basemap.getValue("type").jsonPrimitive.content)
        assertEquals(17, basemap.getValue("maxzoom").jsonPrimitive.content.toInt())
        assertEquals(MapProvider.OpenTopoMap.tileUrls, basemap.getValue("tiles").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `full style shares tile urls with the offline style and adds overlay sources`() {
        val base = Json.parseToJsonElement(MapStyle.baseStyleJson(MapProvider.Osm)).jsonObject
        val full = Json.parseToJsonElement(MapStyle.styleJson(MapBasemap.Online(MapProvider.Osm), palette)).jsonObject

        val baseTiles = base.getValue("sources").jsonObject.getValue(MapStyle.BASEMAP_SOURCE).jsonObject.getValue("tiles")
        val fullSources = full.getValue("sources").jsonObject
        assertEquals(baseTiles, fullSources.getValue(MapStyle.BASEMAP_SOURCE).jsonObject.getValue("tiles"))
        assertTrue(fullSources.keys.containsAll(listOf(MapStyle.AREAS_SOURCE, MapStyle.LINES_SOURCE, MapStyle.POINTS_SOURCE)))
        val layerIds = (full.getValue("layers") as JsonArray).map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(MapStyle.POINTS_LAYER, layerIds.last())
    }

    @Test
    fun `pmtiles pack basemap uses the pmtiles source url`() {
        val header = PmtilesHeader(PmtilesTileType.Png, 0, 14, TileBounds(0.0, 1.0, 0.0, 1.0), GeoPoint(0.5, 0.5), 10)
        val pack = PmtilesPack("field.pmtiles", "field", "/data/packs/field.pmtiles", 1024, header)

        val style = Json.parseToJsonElement(MapStyle.styleJson(MapBasemap.Pack(pack), palette)).jsonObject
        val source = style.getValue("sources").jsonObject.getValue(MapStyle.BASEMAP_SOURCE).jsonObject

        assertEquals("pmtiles://file:///data/packs/field.pmtiles", source.getValue("url").jsonPrimitive.content)
        assertFalse(source.containsKey("tiles"))
    }

    @Test
    fun `css colour keeps alpha`() {
        assertEquals("rgba(58,130,255,1.0)", cssColor(0xFF3A82FF.toInt()))
        assertEquals("rgba(0,0,0,0.6)", cssColor(0x99000000.toInt()))
    }

    @Test
    fun `geojson points request one icon per distinct marker`() {
        val ids = MapIconIds()
        val overlays = MapOverlays(
            waypoints = listOf(
                MapWaypoint("1", GeoPoint(1.0, 2.0), "1"),
                MapWaypoint("2", GeoPoint(1.1, 2.1), "2", selected = true),
            ),
            rally = listOf(MapRallyPoint("r", GeoPoint(1.2, 2.2))),
        )

        val json = Json.parseToJsonElement(MapGeoJson.points(MapVehicle(GeoPoint(1.3, 2.3), 90f), GeoPoint(1.0, 1.0), overlays, ids)).jsonObject

        val features = json.getValue("features").jsonArray
        assertEquals(5, features.size)
        val first = features.first { it.jsonObject.getValue("properties").jsonObject.getValue("icon").jsonPrimitive.content == "vehicle:true:false" }
        // GeoJSON order is longitude, latitude.
        assertEquals(listOf("2.3", "1.3"), first.jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(ids.requested.keys.containsAll(listOf("home", "handle", "wp:Nav:false:1", "wp:Nav:true:2", "rally:false", "vehicle:true:false")))
    }

    @Test
    fun `circles become closed polygon rings`() {
        val overlays = MapOverlays(circles = listOf(MapCircle("c", GeoPoint(10.0, 20.0), 100.0, AreaKind.FenceInclusion)))

        val json = Json.parseToJsonElement(MapGeoJson.areas(overlays, palette)).jsonObject
        val ring = json.getValue("features").jsonArray.single().jsonObject
            .getValue("geometry").jsonObject.getValue("coordinates").jsonArray.single().jsonArray

        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
    }
}
