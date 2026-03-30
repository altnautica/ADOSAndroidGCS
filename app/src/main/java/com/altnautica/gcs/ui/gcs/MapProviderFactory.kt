package com.altnautica.gcs.ui.gcs

import com.altnautica.gcs.ui.settings.MapProvider

/**
 * Returns whether to use Mapbox based on the selected map provider setting.
 */
fun shouldUseMapbox(provider: MapProvider): Boolean {
    return when (provider) {
        MapProvider.MAPBOX -> true
        MapProvider.OSM -> false
    }
}
