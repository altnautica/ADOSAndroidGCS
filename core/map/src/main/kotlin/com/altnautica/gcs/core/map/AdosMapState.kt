package com.altnautica.gcs.core.map

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * Camera state of one [AdosMap]. Camera commands issued before the map is ready
 * are applied once it is.
 */
@Stable
class AdosMapState(initialCenter: GeoPoint?, initialZoom: Double) {
    /** Camera target after the last camera movement settled. */
    var center: GeoPoint? by mutableStateOf(initialCenter)
        private set

    var zoom: Double by mutableDoubleStateOf(initialZoom)
        private set

    /** Area currently on screen, or null until the map has rendered. */
    var visibleBounds: TileBounds? by mutableStateOf(null)
        private set

    private var map: MapLibreMap? = null
    private var controller: MapController? = null
    private var pending: ((MapLibreMap) -> Unit)? = null

    /** Live camera target (null until the map is ready); unlike [center] it follows every frame. */
    val cameraTarget: GeoPoint?
        get() = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }

    /** Changes the zoom by [delta] levels around the view centre. */
    fun zoomBy(delta: Double, animate: Boolean = true) = run { map ->
        val update = CameraUpdateFactory.zoomBy(delta)
        if (animate) map.animateCamera(update) else map.moveCamera(update)
    }

    /**
     * Moves the camera by screen pixels: positive [dxPx] shows more of the right side,
     * positive [dyPx] more of the bottom. Continuous stick input, so it is dropped (not
     * queued) before the map is ready; a queued pan would replace a pending fit.
     */
    fun panBy(dxPx: Float, dyPx: Float) {
        // scrollBy moves the content, the opposite of the camera.
        map?.scrollBy(-dxPx, -dyPx)
    }

    /** The waypoint, rally point or handle under the view centre, if any. */
    fun featureAtCenter(): MapFeatureRef? = controller?.hitTestCenter()?.ref

    /**
     * Acts as a tap at the view centre: a feature there gets the feature-click callback,
     * otherwise the map-click callback receives the centre point.
     */
    fun tapCenter() {
        controller?.tapCenter()
    }

    /** Where [point] sits on screen, in pixels from the map's top-left corner; null until ready. */
    fun screenLocation(point: GeoPoint): Offset? {
        val p: PointF = map?.projection?.toScreenLocation(point.toLatLng()) ?: return null
        return Offset(p.x, p.y)
    }

    fun centerOn(point: GeoPoint, zoom: Double? = null, animate: Boolean = true) = run { map ->
        val update = if (zoom != null) {
            CameraUpdateFactory.newLatLngZoom(point.toLatLng(), zoom)
        } else {
            CameraUpdateFactory.newLatLng(point.toLatLng())
        }
        if (animate) map.animateCamera(update) else map.moveCamera(update)
    }

    /** Frames every point in [points]; a single point is centred at [singlePointZoom]. */
    fun fitTo(points: List<GeoPoint>, paddingPx: Int = 96, singlePointZoom: Double = 16.0) {
        val box = GeoMath.bounds(points) ?: return
        if (box.minLat == box.maxLat && box.minLon == box.maxLon) {
            centerOn(points.first(), singlePointZoom)
            return
        }
        run { map ->
            val bounds = LatLngBounds.Builder()
                .include(LatLng(box.minLat, box.minLon))
                .include(LatLng(box.maxLat, box.maxLon))
                .build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
        }
    }

    private fun run(action: (MapLibreMap) -> Unit) {
        val current = map
        if (current != null) action(current) else pending = action
    }

    internal fun attach(map: MapLibreMap, controller: MapController) {
        this.map = map
        this.controller = controller
        val initial = center
        if (initial != null) map.moveCamera(CameraUpdateFactory.newLatLngZoom(initial.toLatLng(), zoom))
        pending?.invoke(map)
        pending = null
    }

    internal fun detach() {
        map = null
        controller = null
    }

    internal fun onCameraIdle(map: MapLibreMap) {
        val position = map.cameraPosition
        position.target?.let { center = GeoPoint(it.latitude, it.longitude) }
        zoom = position.zoom
        val region = map.projection.visibleRegion.latLngBounds
        visibleBounds = TileBounds(
            minLat = region.getLatSouth(),
            maxLat = region.getLatNorth(),
            minLon = region.getLonWest(),
            maxLon = region.getLonEast(),
        )
    }
}

@Composable
fun rememberAdosMapState(initialCenter: GeoPoint? = null, initialZoom: Double = 15.0): AdosMapState =
    remember { AdosMapState(initialCenter, initialZoom) }

internal fun GeoPoint.toLatLng() = LatLng(latDeg, lonDeg)
