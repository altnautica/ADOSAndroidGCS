package com.altnautica.gcs.core.map

/**
 * Raster basemap providers. URL templates, zoom limits, average tile sizes and
 * attribution strings match the web planner's provider table so both clients
 * request the same tiles and credit the same sources.
 */
enum class MapProvider(
    /** Stable id stored in settings and offline-pack metadata. */
    val id: String,
    /** XYZ template; `{s}` expands to every entry of [subdomains]. */
    val urlTemplate: String,
    val subdomains: List<String>,
    val maxZoom: Int,
    /** Average tile size in KiB, used for offline download size estimates. */
    val avgTileKB: Int,
    /** Attribution as published by the provider (HTML). */
    val attributionHtml: String,
    /** Short uppercase label for the basemap selector. */
    val label: String,
) {
    Osm(
        id = "osm",
        urlTemplate = "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
        subdomains = listOf("a", "b", "c"),
        maxZoom = 19,
        avgTileKB = 25,
        attributionHtml = "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>",
        label = "OSM",
    ),
    EsriWorldImagery(
        id = "satellite",
        urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        subdomains = emptyList(),
        maxZoom = 18,
        avgTileKB = 40,
        attributionHtml = "&copy; <a href=\"https://www.esri.com/\">Esri</a>",
        label = "SAT",
    ),
    OpenTopoMap(
        id = "terrain",
        urlTemplate = "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
        subdomains = listOf("a", "b", "c"),
        maxZoom = 17,
        avgTileKB = 20,
        attributionHtml = "&copy; <a href=\"https://opentopomap.org\">OpenTopoMap</a>",
        label = "TOPO",
    ),
    ;

    /** Concrete tile URLs, one per subdomain. */
    val tileUrls: List<String>
        get() = if (subdomains.isEmpty()) {
            listOf(urlTemplate)
        } else {
            subdomains.map { urlTemplate.replace("{s}", it) }
        }

    /** Attribution as plain text for on-map display. */
    val attributionText: String get() = attributionPlainText(attributionHtml)

    companion object {
        fun fromId(id: String?): MapProvider? = entries.firstOrNull { it.id == id }
    }
}

/** Strips tags and decodes the few entities used in attribution strings. */
fun attributionPlainText(html: String): String =
    html.replace(Regex("<[^>]*>"), "")
        .replace("&copy;", "\u00A9")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .trim()

/** Localised display name of the provider. */
val MapProvider.titleRes: Int
    get() = when (this) {
        MapProvider.Osm -> R.string.map_provider_osm
        MapProvider.EsriWorldImagery -> R.string.map_provider_satellite
        MapProvider.OpenTopoMap -> R.string.map_provider_terrain
    }
