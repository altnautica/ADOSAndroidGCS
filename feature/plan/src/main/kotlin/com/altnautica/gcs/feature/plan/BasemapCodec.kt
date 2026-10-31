package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.PmtilesPack

/** Stored form of the planner basemap: `online:<providerId>` or `pmtiles:<packId>`. */
internal object BasemapCodec {
    private const val ONLINE = "online:"
    private const val PMTILES = "pmtiles:"
    val DEFAULT: MapBasemap = MapBasemap.Online(MapProvider.Osm)

    fun encode(basemap: MapBasemap): String = when (basemap) {
        is MapBasemap.Online -> ONLINE + basemap.provider.id
        is MapBasemap.Pack -> PMTILES + basemap.pack.id
    }

    /** The stored basemap; [DEFAULT] when nothing is stored or the provider or pack is gone. */
    fun decode(stored: String?, packs: List<PmtilesPack>): MapBasemap = when {
        stored == null -> DEFAULT
        stored.startsWith(ONLINE) -> stored.removePrefix(ONLINE).let { id -> MapProvider.entries.firstOrNull { it.id == id } }?.let { MapBasemap.Online(it) } ?: DEFAULT
        stored.startsWith(PMTILES) -> stored.removePrefix(PMTILES).let { id -> packs.firstOrNull { it.id == id } }?.let { MapBasemap.Pack(it) } ?: DEFAULT
        else -> DEFAULT
    }
}
