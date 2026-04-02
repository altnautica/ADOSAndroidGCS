package com.altnautica.gcs.ui.gcs

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * Mode for the geofence drawing tool.
 */
enum class FenceDrawMode {
    POLYGON,
    CIRCLE,
}

/**
 * State holder for geofence editor. Shared between the toolbar composable
 * and the imperative OSMDroid map overlay logic.
 */
class GeofenceEditorState {
    var drawMode by mutableStateOf(FenceDrawMode.POLYGON)
    var isDrawing by mutableStateOf(false)
    val polygonPoints = mutableStateListOf<GeoPoint>()
    var circleCenter by mutableStateOf<GeoPoint?>(null)
    var circleRadiusM by mutableFloatStateOf(100f)
    var isClosed by mutableStateOf(false)

    fun clear() {
        polygonPoints.clear()
        circleCenter = null
        circleRadiusM = 100f
        isClosed = false
        isDrawing = false
    }

    val hasGeofence: Boolean
        get() = isClosed || circleCenter != null
}

/**
 * Toolbar overlay for the geofence editor. Sits on top of the map screen.
 *
 * @param state Shared geofence editor state.
 * @param onUpload Called when the user taps "Upload Fence". Receives the fence
 *        vertices (polygon) or center+radius (circle).
 */
@Composable
fun GeofenceEditorToolbar(
    state: GeofenceEditorState,
    onUpload: (vertices: List<GeoPoint>, radiusM: Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DeepBlack.copy(alpha = 0.9f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Geofence",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))

                // Mode toggle: polygon vs circle
                IconButton(
                    onClick = {
                        state.drawMode = FenceDrawMode.POLYGON
                        state.clear()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Draw,
                        contentDescription = "Polygon fence",
                        tint = if (state.drawMode == FenceDrawMode.POLYGON) ElectricBlue
                        else OnSurfaceMedium,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = {
                        state.drawMode = FenceDrawMode.CIRCLE
                        state.clear()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.RadioButtonChecked,
                        contentDescription = "Circle fence",
                        tint = if (state.drawMode == FenceDrawMode.CIRCLE) ElectricBlue
                        else OnSurfaceMedium,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Drawing toggle
            if (!state.isDrawing && !state.hasGeofence) {
                Button(
                    onClick = { state.isDrawing = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Draw, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Draw Geofence")
                }
            }

            // Drawing status
            if (state.isDrawing) {
                val hint = when (state.drawMode) {
                    FenceDrawMode.POLYGON -> "Tap map to add vertices. Tap near the first point to close."
                    FenceDrawMode.CIRCLE -> "Tap map to set center point."
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                )
                if (state.drawMode == FenceDrawMode.POLYGON) {
                    Text(
                        text = "${state.polygonPoints.size} points",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMedium,
                    )
                }
            }

            // Circle radius slider (only when circle mode and center is set)
            if (state.drawMode == FenceDrawMode.CIRCLE && state.circleCenter != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Radius: ${state.circleRadiusM.toInt()}m",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceMedium,
                )
                Slider(
                    value = state.circleRadiusM,
                    onValueChange = { state.circleRadiusM = it },
                    valueRange = 20f..2000f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricBlue,
                        activeTrackColor = ElectricBlue,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Action buttons
            if (state.hasGeofence) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { state.clear() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                    Button(
                        onClick = {
                            val center = state.circleCenter
                            if (state.drawMode == FenceDrawMode.CIRCLE && center != null) {
                                onUpload(listOf(center), state.circleRadiusM)
                            } else {
                                onUpload(state.polygonPoints.toList(), null)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Upload")
                    }
                }
            }

            // Cancel drawing
            if (state.isDrawing) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { state.clear() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Attaches geofence drawing overlays to an OSMDroid [MapView].
 * Call this from inside an AndroidView update block to wire tap-to-add-vertex
 * and polygon rendering.
 *
 * Returns the polygon overlay for external management (removal, etc.).
 */
fun attachGeofenceOverlay(
    mapView: MapView,
    state: GeofenceEditorState,
): Polygon? {
    if (!state.isDrawing && !state.hasGeofence) return null

    // Draw the polygon or circle overlay
    val polygon = Polygon(mapView).apply {
        fillPaint.color = AndroidColor.argb(50, 239, 68, 68) // semi-transparent red
        outlinePaint.color = AndroidColor.argb(180, 239, 68, 68)
        outlinePaint.strokeWidth = 3f
    }

    when {
        state.drawMode == FenceDrawMode.CIRCLE && state.circleCenter != null -> {
            // Generate circle polygon from center + radius
            val center = state.circleCenter!!
            val circlePoints = generateCirclePoints(center, state.circleRadiusM, 36)
            polygon.points = circlePoints
            state.isClosed = true
        }
        state.drawMode == FenceDrawMode.POLYGON && state.polygonPoints.size >= 3 && state.isClosed -> {
            polygon.points = state.polygonPoints.toList()
        }
        state.drawMode == FenceDrawMode.POLYGON && state.polygonPoints.size >= 2 -> {
            // Open polygon preview (not closed yet)
            polygon.points = state.polygonPoints.toList()
        }
        else -> return null
    }

    // Remove any existing geofence overlays before adding new one
    mapView.overlays.removeAll { it is Polygon && it.title == "geofence" }
    polygon.title = "geofence"
    mapView.overlays.add(polygon)
    mapView.invalidate()

    return polygon
}

/**
 * Sets up a tap listener for adding geofence vertices on an OSMDroid MapView.
 * Should be called once in the AndroidView factory block.
 */
fun setupGeofenceTapListener(
    mapView: MapView,
    state: GeofenceEditorState,
) {
    mapView.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
        override fun onSingleTapConfirmed(
            e: android.view.MotionEvent?,
            mapView: MapView?,
        ): Boolean {
            if (!state.isDrawing || e == null || mapView == null) return false

            val projection = mapView.projection
            val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint

            when (state.drawMode) {
                FenceDrawMode.POLYGON -> {
                    if (state.polygonPoints.size >= 3) {
                        // Check if tap is near the first point (within ~30 pixels) to close
                        val firstPoint = state.polygonPoints.first()
                        val firstPixel = projection.toPixels(firstPoint, null)
                        val tapPixel = android.graphics.Point(e.x.toInt(), e.y.toInt())
                        val dx = firstPixel.x - tapPixel.x
                        val dy = firstPixel.y - tapPixel.y
                        val distPx = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())

                        if (distPx < 40) {
                            // Close the polygon
                            state.isClosed = true
                            state.isDrawing = false
                            attachGeofenceOverlay(mapView, state)
                            return true
                        }
                    }
                    state.polygonPoints.add(geoPoint)
                    attachGeofenceOverlay(mapView, state)
                }
                FenceDrawMode.CIRCLE -> {
                    state.circleCenter = geoPoint
                    state.isDrawing = false
                    attachGeofenceOverlay(mapView, state)
                }
            }
            return true
        }
    })
}

/**
 * Generate a list of [GeoPoint]s approximating a circle on the globe.
 */
private fun generateCirclePoints(
    center: GeoPoint,
    radiusM: Float,
    segments: Int,
): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    for (i in 0..segments) {
        val angle = Math.toRadians((360.0 / segments) * i)
        val dLat = radiusM * kotlin.math.cos(angle) / 111_320.0
        val dLon = radiusM * kotlin.math.sin(angle) /
            (111_320.0 * kotlin.math.cos(Math.toRadians(center.latitude)))
        points.add(GeoPoint(center.latitude + dLat, center.longitude + dLon))
    }
    return points
}

/**
 * Upload fence vertices to the flight controller via MAVLink.
 *
 * Uses MAV_CMD_DO_FENCE_ENABLE to enable the geofence, followed by
 * the fence point protocol. The fence upload protocol requires:
 * 1. FENCE_TOTAL (param set) to declare vertex count
 * 2. Individual FENCE_POINT messages for each vertex
 *
 * TODO: Implement full fence point protocol upload. The dronefleet library
 *       has limited fence message support. For now this enables the fence
 *       and logs the vertices. The visual editor is fully functional.
 *       Full implementation requires sending PARAM_SET for FENCE_TOTAL,
 *       then FENCE_POINT (msg 160) for each vertex.
 */
suspend fun uploadGeofence(
    vertices: List<GeoPoint>,
    radiusM: Float?,
    commandSender: com.altnautica.gcs.data.mavlink.MavLinkCommandSender,
) {
    android.util.Log.i("GeofenceEditor", "Upload fence: ${vertices.size} vertices, radius=$radiusM")

    // Enable geofence: MAV_CMD_DO_FENCE_ENABLE = 207
    commandSender.sendCommandLongRaw(
        commandId = 207,
        param1 = 1f, // 1 = enable
    )

    // If circular fence, set FENCE_RADIUS parameter
    if (radiusM != null) {
        commandSender.sendParamSet("FENCE_RADIUS", radiusM, 9) // 9 = MAV_PARAM_TYPE_REAL32
        android.util.Log.i("GeofenceEditor", "Set FENCE_RADIUS=$radiusM")
    }

    // Set fence action to RTL (configurable later)
    commandSender.sendParamSet("FENCE_ACTION", 1f, 9) // 1 = RTL

    // TODO: Upload polygon fence points via FENCE_POINT protocol.
    // This requires custom message encoding not available in dronefleet.
    // For polygon fences, the visual overlay is accurate and vertices are
    // logged here for manual verification.
    for ((i, pt) in vertices.withIndex()) {
        android.util.Log.i(
            "GeofenceEditor",
            "Fence vertex $i: lat=${pt.latitude}, lon=${pt.longitude}"
        )
    }
}
