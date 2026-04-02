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
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotation
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager

/**
 * Coordinate pair for overlay polylines on the map.
 * Kept here to avoid coupling map view to mission data types.
 */
data class MapLatLng(val lat: Double, val lon: Double)

/**
 * Dual-provider drone map view. Switches between Mapbox (online, dark style)
 * and OSMDroid (offline-capable, CARTO dark tiles) based on user preference.
 * Optionally renders an overlay polyline (survey grid preview, flight path, etc.).
 */
@Composable
fun DroneMapView(
    dronePosition: PositionState,
    homePosition: PositionState?,
    useMapbox: Boolean = true,
    overlayPolyline: List<MapLatLng> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (useMapbox) {
        MapboxMapView(dronePosition, homePosition, overlayPolyline, modifier)
    } else {
        OsmMapView(dronePosition, homePosition, modifier)
    }
}

@Composable
private fun MapboxMapView(
    dronePosition: PositionState,
    homePosition: PositionState?,
    overlayPolyline: List<MapLatLng>,
    modifier: Modifier,
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var droneAnnotation by remember { mutableStateOf<PointAnnotation?>(null) }
    var homeAnnotation by remember { mutableStateOf<PointAnnotation?>(null) }
    var annotationManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
    var polylineManager by remember { mutableStateOf<PolylineAnnotationManager?>(null) }
    var polylineAnnotation by remember { mutableStateOf<PolylineAnnotation?>(null) }
    var autoCenter by remember { mutableStateOf(true) }

    val droneLat = dronePosition.lat
    val droneLon = dronePosition.lon

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).also { mv ->
                    mapView = mv
                    mv.mapboxMap.loadStyle(Style.DARK) {
                        val am = mv.annotations.createPointAnnotationManager()
                        annotationManager = am
                        val pm = mv.annotations.createPolylineAnnotationManager()
                        polylineManager = pm
                    }
                }
            },
            update = { mv ->
                val am = annotationManager ?: return@AndroidView

                // Update drone marker
                if (droneLat != 0.0 && droneLon != 0.0) {
                    val point = Point.fromLngLat(droneLon, droneLat)

                    if (droneAnnotation == null) {
                        val options = PointAnnotationOptions()
                            .withPoint(point)
                            .withTextField("\u25B2")
                            .withTextColor("#3A82FF")
                            .withTextSize(20.0)
                        droneAnnotation = am.create(options)
                    } else {
                        droneAnnotation?.point = point
                        am.update(droneAnnotation!!)
                    }

                    if (autoCenter) {
                        mv.mapboxMap.setCamera(
                            CameraOptions.Builder()
                                .center(point)
                                .zoom(16.0)
                                .bearing(dronePosition.heading.toDouble())
                                .build()
                        )
                    }
                }

                // Update home marker
                val homeLat = homePosition?.lat ?: 0.0
                val homeLon = homePosition?.lon ?: 0.0
                if (homeLat != 0.0 && homeLon != 0.0) {
                    val homePoint = Point.fromLngLat(homeLon, homeLat)
                    if (homeAnnotation == null) {
                        val options = PointAnnotationOptions()
                            .withPoint(homePoint)
                            .withTextField("H")
                            .withTextColor("#22C55E")
                            .withTextSize(16.0)
                        homeAnnotation = am.create(options)
                    } else {
                        homeAnnotation?.point = homePoint
                        am.update(homeAnnotation!!)
                    }
                }

                // Update overlay polyline (survey preview, etc.)
                val pm = polylineManager
                if (pm != null) {
                    if (overlayPolyline.size >= 2) {
                        val points = overlayPolyline.map { Point.fromLngLat(it.lon, it.lat) }
                        if (polylineAnnotation == null) {
                            val options = PolylineAnnotationOptions()
                                .withPoints(points)
                                .withLineColor("#3A82FF")
                                .withLineWidth(2.0)
                                .withLineOpacity(0.8)
                            polylineAnnotation = pm.create(options)
                        } else {
                            polylineAnnotation?.points = points
                            pm.update(polylineAnnotation!!)
                        }
                    } else if (polylineAnnotation != null) {
                        pm.delete(polylineAnnotation!!)
                        polylineAnnotation = null
                    }
                }
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
