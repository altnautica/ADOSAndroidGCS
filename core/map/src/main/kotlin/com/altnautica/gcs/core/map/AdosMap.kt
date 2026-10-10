package com.altnautica.gcs.core.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.hypot

/** [Full] is the interactive planning map; [Minimap] is a small vehicle-following inset. */
enum class AdosMapMode { Full, Minimap }

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
    private var pending: ((MapLibreMap) -> Unit)? = null

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

    internal fun attach(map: MapLibreMap) {
        this.map = map
        val initial = center
        if (initial != null) map.moveCamera(CameraUpdateFactory.newLatLngZoom(initial.toLatLng(), zoom))
        pending?.invoke(map)
        pending = null
    }

    internal fun detach() {
        map = null
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

/**
 * MapLibre map with the ADOS raster basemaps and mission overlays.
 *
 * Feature callbacks receive taps, long presses and drags on waypoints, rally
 * points and handles; [onMapClick] / [onMapLongClick] receive the rest. A
 * non-[interactive] map ignores every gesture. With [followVehicle] the camera
 * follows [vehicle], or [home] until the vehicle reports a position; the first
 * follow move sets [followZoom] (the current zoom when null).
 */
@Composable
fun AdosMap(
    modifier: Modifier = Modifier,
    state: AdosMapState = rememberAdosMapState(),
    basemap: MapBasemap = MapBasemap.Online(MapProvider.Osm),
    mode: AdosMapMode = AdosMapMode.Full,
    interactive: Boolean = mode == AdosMapMode.Full,
    vehicle: MapVehicle? = null,
    home: GeoPoint? = null,
    trail: List<GeoPoint> = emptyList(),
    overlays: MapOverlays = MapOverlays.Empty,
    followVehicle: Boolean = mode == AdosMapMode.Minimap,
    followZoom: Double? = null,
    textureMode: Boolean = mode == AdosMapMode.Minimap,
    onMapClick: ((GeoPoint) -> Unit)? = null,
    onMapLongClick: ((GeoPoint) -> Unit)? = null,
    onFeatureClick: ((MapFeatureRef) -> Unit)? = null,
    onFeatureLongClick: ((MapFeatureRef) -> Unit)? = null,
    onFeatureDrag: ((MapFeatureRef, GeoPoint, DragPhase) -> Unit)? = null,
    onLoadError: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val colors = AdosTheme.colors
    val palette = remember(colors) { colors.mapPalette() }
    val iconPalette = remember(colors) { colors.iconPalette() }
    val controller = remember { MapController(density) }
    val mapView = remember(textureMode) { createMapView(context, textureMode) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        controller.bind(mapView, state)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            val current = lifecycle.currentState
            if (current.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (current.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            controller.unbind(state)
            mapView.onDestroy()
        }
    }

    SideEffect {
        controller.callbacks = MapCallbacks(onMapClick, onMapLongClick, onFeatureClick, onFeatureLongClick, onFeatureDrag, onLoadError)
        controller.setGestures(interactive, mode)
        controller.setStyle(MapStyle.styleJson(basemap, palette), MapIconFactory(density, iconPalette))
        controller.setData(MapData(vehicle, home, trail, overlays, palette))
        if (followVehicle) (vehicle?.position ?: home)?.let { controller.follow(it, followZoom ?: state.zoom) }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Attribution(basemap, mode, Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * Non-interactive inset that follows the vehicle and shows home and the live trail.
 * [onClick] (for example swapping the inset with the main view) receives taps.
 */
@Composable
fun AdosMinimap(
    vehicle: MapVehicle?,
    home: GeoPoint?,
    trail: List<GeoPoint>,
    modifier: Modifier = Modifier,
    basemap: MapBasemap = MapBasemap.Online(MapProvider.Osm),
    overlays: MapOverlays = MapOverlays.Empty,
    zoom: Double = 16.0,
    onClick: (() -> Unit)? = null,
) {
    val state = rememberAdosMapState(initialCenter = vehicle?.position ?: home, initialZoom = zoom)
    val description = stringResource(R.string.map_minimap_description)
    Box(modifier.semantics { contentDescription = description }) {
        AdosMap(
            modifier = Modifier.fillMaxSize(),
            state = state,
            basemap = basemap,
            mode = AdosMapMode.Minimap,
            interactive = false,
            vehicle = vehicle,
            home = home,
            trail = trail,
            overlays = overlays,
            followZoom = zoom,
        )
        if (vehicle == null) {
            Text(
                text = stringResource(R.string.map_no_position),
                style = MaterialTheme.typography.labelSmall,
                color = AdosTheme.colors.hudInk,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(AdosTheme.colors.hudGlass, RoundedCornerShape(AdosRadius.Md))
                    .padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1),
            )
        }
        if (onClick != null) {
            Box(Modifier.fillMaxSize().clickable(onClick = onClick))
        }
    }
}

@Composable
private fun Attribution(basemap: MapBasemap, mode: AdosMapMode, modifier: Modifier) {
    val text = when (basemap) {
        is MapBasemap.Online -> basemap.provider.attributionText
        is MapBasemap.Pack -> basemap.pack.name
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AdosTheme.colors.hudInk,
        maxLines = 1,
        modifier = modifier
            .padding(if (mode == AdosMapMode.Minimap) AdosSpacing.S1 / 2 else AdosSpacing.S1)
            .background(AdosTheme.colors.hudGlass, RoundedCornerShape(AdosRadius.Sm))
            .padding(horizontal = AdosSpacing.S1),
    )
}

private fun createMapView(context: Context, textureMode: Boolean): MapView {
    MapLibre.getInstance(context.applicationContext)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(textureMode)
        .attributionEnabled(false)
        .logoEnabled(false)
    return MapView(context, options).apply { onCreate(null) }
}

private fun GeoPoint.toLatLng() = LatLng(latDeg, lonDeg)

private fun AdosColorRoles.mapPalette() = MapPalette(
    background = bgCanvas.toArgb(),
    missionPath = accentPrimary.toArgb(),
    trail = hudPrimary.toArgb(),
    track = accentSecondary.toArgb(),
    surveyPreview = accentSecondary.toArgb(),
    fenceInclusion = statusSuccess.toArgb(),
    fenceExclusion = statusError.toArgb(),
    survey = accentSecondary.toArgb(),
    draft = fgSecondary.toArgb(),
)

private fun AdosColorRoles.iconPalette() = IconPalette(
    nav = accentPrimary.toArgb(),
    takeoff = statusSuccess.toArgb(),
    land = statusWarning.toArgb(),
    returnHome = statusSerious.toArgb(),
    loiter = hudDim.toArgb(),
    roi = hudMuted.toArgb(),
    selected = accentSecondary.toArgb(),
    home = statusSuccess.toArgb(),
    rally = statusWarning.toArgb(),
    vehicle = hudPrimary.toArgb(),
    vehicleStale = hudMuted.toArgb(),
    ink = hudInk.toArgb(),
    outline = bgCanvas.toArgb(),
)

private class MapCallbacks(
    val onMapClick: ((GeoPoint) -> Unit)?,
    val onMapLongClick: ((GeoPoint) -> Unit)?,
    val onFeatureClick: ((MapFeatureRef) -> Unit)?,
    val onFeatureLongClick: ((MapFeatureRef) -> Unit)?,
    val onFeatureDrag: ((MapFeatureRef, GeoPoint, DragPhase) -> Unit)?,
    val onLoadError: ((String) -> Unit)?,
)

private data class MapData(
    val vehicle: MapVehicle?,
    val home: GeoPoint?,
    val trail: List<GeoPoint>,
    val overlays: MapOverlays,
    val palette: MapPalette,
)

/** Owns the MapView side: style switching, source updates, icon registration and touch handling. */
private class MapController(private val density: Float) {
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
