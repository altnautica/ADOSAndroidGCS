package com.altnautica.gcs.core.map

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.hypot

internal class MapCallbacks(
    val onMapClick: ((GeoPoint) -> Unit)?,
    val onMapLongClick: ((GeoPoint) -> Unit)?,
    val onFeatureClick: ((MapFeatureRef) -> Unit)?,
    val onFeatureLongClick: ((MapFeatureRef) -> Unit)?,
    val onFeatureDrag: ((MapFeatureRef, GeoPoint, DragPhase) -> Unit)?,
    val onLoadError: ((String) -> Unit)?,
)

internal data class MapData(
    val vehicle: MapVehicle?,
    val home: GeoPoint?,
    val trail: List<GeoPoint>,
    val overlays: MapOverlays,
    val palette: MapPalette,
)

/** Owns the MapView side: style switching, source updates, icon registration and touch handling. */
internal class MapController(private val density: Float) {
    var callbacks: MapCallbacks? = null

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var requestedStyle: String? = null
    private var iconFactory: MapIconFactory? = null
    private val registeredIcons = HashSet<String>()
    private var data: MapData? = null
    private var appliedAreas: Pair<MapOverlays, MapPalette>? = null
    private var appliedLines: Triple<List<GeoPoint>, MapOverlays, MapPalette>? = null
    private var appliedPoints: MapData? = null
    private var interactive = true
    private var mode = AdosMapMode.Full
    private var interactivePoints: List<InteractivePoint> = emptyList()
    private val followCamera = CameraFollow()

    private val handler = Handler(Looper.getMainLooper())
    private var downRef: InteractivePoint? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        val ref = downRef
        if (ref != null && !dragging) {
            longPressed = true
            callbacks?.onFeatureLongClick?.invoke(ref.ref)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(view: MapView, state: AdosMapState) {
        mapView = view
        view.addOnDidFailLoadingMapListener { message -> callbacks?.onLoadError?.invoke(message) }
        view.setOnTouchListener { v, event ->
            onTouch(event).also { consumed -> if (consumed && event.actionMasked == MotionEvent.ACTION_UP) v.performClick() }
        }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            applyGestures()
            m.addOnMapClickListener { latLng ->
                val cb = callbacks?.onMapClick ?: return@addOnMapClickListener false
                cb(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            m.addOnMapLongClickListener { latLng ->
                val cb = callbacks?.onMapLongClick ?: return@addOnMapLongClickListener false
                cb(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            m.addOnCameraIdleListener { state.onCameraIdle(m) }
            state.attach(m)
            applyFollow()
            requestedStyle?.let { loadStyle(m, it) }
        }
    }

    fun unbind(state: AdosMapState) {
        handler.removeCallbacks(longPress)
        followCamera.reset()
        mapView?.setOnTouchListener(null)
        mapView = null
        map = null
        style = null
    }

    fun setGestures(interactive: Boolean, mode: AdosMapMode) {
        if (this.interactive == interactive && this.mode == mode && map != null) return
        this.interactive = interactive
        this.mode = mode
        applyGestures()
    }

    private fun applyGestures() {
        val ui = map?.uiSettings ?: return
        ui.setAllGesturesEnabled(interactive)
        ui.isTiltGesturesEnabled = false
        ui.isCompassEnabled = interactive && mode == AdosMapMode.Full
    }

    fun setStyle(json: String, icons: MapIconFactory) {
        if (json == requestedStyle) return
        requestedStyle = json
        iconFactory = icons
        map?.let { loadStyle(it, json) }
    }

    private fun loadStyle(m: MapLibreMap, json: String) {
        style = null
        registeredIcons.clear()
        appliedAreas = null
        appliedLines = null
        appliedPoints = null
        m.setStyle(Style.Builder().fromJson(json)) { loaded ->
            if (requestedStyle != json) return@setStyle
            style = loaded
            applyData()
        }
    }

    fun setData(next: MapData) {
        data = next
        interactivePoints = interactivePoints(next.overlays)
        applyData()
    }

    /** Follows [position]; applied now when the map is ready, else as soon as it is. */
    fun follow(position: GeoPoint, zoom: Double) {
        followCamera.request(position, zoom)
        applyFollow()
    }

    private fun applyFollow() {
        val m = map ?: return
        val move = followCamera.next() ?: return
        val target = LatLng(move.target.latDeg, move.target.lonDeg)
        val zoom = move.zoom
        m.moveCamera(if (zoom != null) CameraUpdateFactory.newLatLngZoom(target, zoom) else CameraUpdateFactory.newLatLng(target))
    }

    private fun applyData() {
        val s = style ?: return
        val d = data ?: return
        val areasKey = d.overlays to d.palette
        if (appliedAreas != areasKey) {
            s.getSourceAs<GeoJsonSource>(MapStyle.AREAS_SOURCE)?.setGeoJson(MapGeoJson.areas(d.overlays, d.palette))
            appliedAreas = areasKey
        }
        val linesKey = Triple(d.trail, d.overlays, d.palette)
        if (appliedLines != linesKey) {
            s.getSourceAs<GeoJsonSource>(MapStyle.LINES_SOURCE)?.setGeoJson(MapGeoJson.lines(d.trail, d.overlays, d.palette))
            appliedLines = linesKey
        }
        if (appliedPoints != d) {
            val ids = MapIconIds()
            val json = MapGeoJson.points(d.vehicle, d.home, d.overlays, ids)
            val factory = iconFactory
            if (factory != null) {
                ids.requested.values.forEach { spec ->
                    if (registeredIcons.add(spec.id)) s.addImage(spec.id, factory.create(spec))
                }
            }
            s.getSourceAs<GeoJsonSource>(MapStyle.POINTS_SOURCE)?.setGeoJson(json)
            appliedPoints = d
        }
    }

    private fun hitTest(x: Float, y: Float): InteractivePoint? {
        val m = map ?: return null
        val radius = HIT_RADIUS_DP * density
        var best: InteractivePoint? = null
        var bestDistance = Float.MAX_VALUE
        for (point in interactivePoints) {
            val screen = m.projection.toScreenLocation(point.position.toLatLng())
            val d = hypot(screen.x - x, screen.y - y)
            if (d <= radius && d <= bestDistance) {
                best = point
                bestDistance = d
            }
        }
        return best
    }

    private fun onTouch(event: MotionEvent): Boolean {
        val cb = callbacks
        val wantsFeatures = interactive && mode == AdosMapMode.Full &&
            (cb?.onFeatureClick != null || cb?.onFeatureLongClick != null || cb?.onFeatureDrag != null)
        if (!wantsFeatures && downRef == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.x, event.y) ?: return false
                downRef = hit
                downX = event.x
                downY = event.y
                dragging = false
                longPressed = false
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val ref = downRef ?: return false
                val slop = mapView?.let { ViewConfiguration.get(it.context).scaledTouchSlop } ?: 16
                if (!dragging && !longPressed && ref.draggable && cb?.onFeatureDrag != null &&
                    hypot(event.x - downX, event.y - downY) > slop
                ) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    cb.onFeatureDrag.invoke(ref.ref, screenToGeo(event.x, event.y) ?: ref.position, DragPhase.Start)
                }
                if (dragging) screenToGeo(event.x, event.y)?.let { cb?.onFeatureDrag?.invoke(ref.ref, it, DragPhase.Move) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val ref = downRef ?: return false
                handler.removeCallbacks(longPress)
                if (dragging) {
                    cb?.onFeatureDrag?.invoke(ref.ref, screenToGeo(event.x, event.y) ?: ref.position, DragPhase.End)
                } else if (!longPressed) {
                    cb?.onFeatureClick?.invoke(ref.ref)
                }
                resetTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val ref = downRef ?: return false
                handler.removeCallbacks(longPress)
                if (dragging) cb?.onFeatureDrag?.invoke(ref.ref, ref.position, DragPhase.End)
                resetTouch()
                return true
            }
            else -> return downRef != null
        }
    }

    private fun resetTouch() {
        downRef = null
        dragging = false
        longPressed = false
    }

    private fun screenToGeo(x: Float, y: Float): GeoPoint? {
        val latLng = map?.projection?.fromScreenLocation(PointF(x, y)) ?: return null
        return GeoPoint(latLng.latitude, latLng.longitude)
    }

    private companion object {
        const val HIT_RADIUS_DP = 22f
    }
}
