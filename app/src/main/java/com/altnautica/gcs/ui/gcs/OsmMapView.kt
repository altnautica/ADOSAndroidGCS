package com.altnautica.gcs.ui.gcs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.altnautica.gcs.data.telemetry.PositionState
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private val CartoDarkTileSource = XYTileSource(
    "CartoDark",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/",
    )
)

/**
 * OSMDroid-based map view with CARTO dark tiles. Offline-capable fallback
 * when Mapbox is unavailable or the user prefers open-source maps.
 */
@Composable
fun OsmMapView(
    dronePosition: PositionState,
    homePosition: PositionState?,
    modifier: Modifier = Modifier,
) {
    var droneMarker by remember { mutableStateOf<Marker?>(null) }
    var homeMarker by remember { mutableStateOf<Marker?>(null) }
    var autoCenter by remember { mutableStateOf(true) }

    val droneLat = dronePosition.lat
    val droneLon = dronePosition.lon

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(CartoDarkTileSource)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    setBackgroundColor(android.graphics.Color.parseColor("#0A0A0F"))

                    // Drone marker
                    val dm = Marker(this).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Drone"
                    }
                    overlays.add(dm)
                    droneMarker = dm

                    // Home marker
                    val hm = Marker(this).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Home"
                    }
                    overlays.add(hm)
                    homeMarker = hm
                }
            },
            update = { mv ->
                // Update drone marker position
                if (droneLat != 0.0 && droneLon != 0.0) {
                    val geoPoint = GeoPoint(droneLat, droneLon)
                    droneMarker?.position = geoPoint
                    droneMarker?.rotation = dronePosition.heading.toFloat()

                    if (autoCenter) {
                        mv.controller.animateTo(geoPoint)
                    }
                }

                // Update home marker
                val homeLat = homePosition?.lat ?: 0.0
                val homeLon = homePosition?.lon ?: 0.0
                if (homeLat != 0.0 && homeLon != 0.0) {
                    homeMarker?.position = GeoPoint(homeLat, homeLon)
                }

                mv.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Auto-center toggle
        IconButton(
            onClick = { autoCenter = !autoCenter },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = if (autoCenter) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
                contentDescription = "Toggle auto-center",
                tint = if (autoCenter) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
