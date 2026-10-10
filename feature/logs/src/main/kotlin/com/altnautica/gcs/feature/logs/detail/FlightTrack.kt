package com.altnautica.gcs.feature.logs.detail

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.altnautica.gcs.core.map.AdosMap
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.LineKind
import com.altnautica.gcs.core.map.MapLine
import com.altnautica.gcs.core.map.MapOverlays
import com.altnautica.gcs.core.map.rememberAdosMapState

/** Ground track of a flight on the map, framed to the whole track; the start is marked as home. */
@Composable
fun FlightTrack(track: List<GeoPoint>, modifier: Modifier = Modifier) {
    if (track.size < 2) return
    val state = rememberAdosMapState(initialCenter = track.first())
    val overlays = remember(track) { MapOverlays(lines = listOf(MapLine("track", track, LineKind.Track))) }
    LaunchedEffect(track) { state.fitTo(track) }
    AdosMap(
        modifier = modifier.aspectRatio(1.6f),
        state = state,
        interactive = true,
        home = track.first(),
        overlays = overlays,
        // Hosted inside scrolling content.
        textureMode = true,
    )
}
