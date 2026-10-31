package com.altnautica.gcs.core.map

import android.content.Context
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
import androidx.compose.runtime.remember
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
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/** [Full] is the interactive planning map; [Minimap] is a small vehicle-following inset. */
enum class AdosMapMode { Full, Minimap }

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

private fun AdosColorRoles.mapPalette() = MapPalette(
    background = bgCanvas.toArgb(),
    missionPath = accentPrimary.toArgb(),
    trail = hudPrimary.toArgb(),
    track = fgPrimary.toArgb(),
    surveyPreview = accentPrimaryHover.toArgb(),
    fenceInclusion = statusSuccess.toArgb(),
    fenceExclusion = statusError.toArgb(),
    survey = accentPrimaryHover.toArgb(),
    draft = fgSecondary.toArgb(),
)

private fun AdosColorRoles.iconPalette() = IconPalette(
    nav = accentPrimary.toArgb(),
    takeoff = statusSuccess.toArgb(),
    land = statusWarning.toArgb(),
    returnHome = statusSerious.toArgb(),
    loiter = hudDim.toArgb(),
    roi = hudMuted.toArgb(),
    selected = fgPrimary.toArgb(),
    home = statusSuccess.toArgb(),
    rally = statusWarning.toArgb(),
    vehicle = hudPrimary.toArgb(),
    vehicleStale = hudMuted.toArgb(),
    ink = hudInk.toArgb(),
    outline = bgCanvas.toArgb(),
)
