package com.altnautica.gcs.ui.agriculture

import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber
import com.altnautica.gcs.util.PermissionManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

@Composable
fun FieldMapper(
    onFinish: (List<LatLon>, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = remember { mutableStateListOf<LatLon>() }
    var walking by remember { mutableStateOf(false) }
    var gpsAccuracy by remember { mutableStateOf(0f) }

    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    if (walking) {
                        points.add(LatLon(loc.latitude, loc.longitude))
                        gpsAccuracy = loc.accuracy
                    }
                }
            }
        }
    }

    val startWalking = {
        walking = true
        points.clear()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateDistanceMeters(1f)
            .build()
        if (PermissionManager.hasLocationPermission(context)) {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    val stopWalking = {
        walking = false
        fusedClient.removeLocationUpdates(locationCallback)
        if (points.size >= 3) {
            val area = computeAreaHectares(points)
            onFinish(points.toList(), area)
        }
    }

    // Clean up location updates when composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            fusedClient.removeLocationUpdates(locationCallback)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Map preview with polyline
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.size >= 2) {
                val path = Path()
                // Simple projection: normalize lat/lon to canvas coords
                val minLat = points.minOf { it.lat }
                val maxLat = points.maxOf { it.lat }
                val minLon = points.minOf { it.lon }
                val maxLon = points.maxOf { it.lon }
                val latRange = (maxLat - minLat).coerceAtLeast(0.0001)
                val lonRange = (maxLon - minLon).coerceAtLeast(0.0001)
                val padding = 40.dp.toPx()

                points.forEachIndexed { index, pt ->
                    val x = padding + ((pt.lon - minLon) / lonRange * (size.width - padding * 2)).toFloat()
                    val y = padding + ((maxLat - pt.lat) / latRange * (size.height - padding * 2)).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)

                    // Draw point dot
                    drawCircle(
                        color = ElectricBlue,
                        radius = 4.dp.toPx(),
                        center = Offset(x, y),
                    )
                }

                // Close polygon if 3+ points
                if (points.size >= 3) {
                    path.close()
                }

                drawPath(
                    path = path,
                    color = ElectricBlue,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // GPS accuracy indicator (top-left)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val accColor = when {
                    gpsAccuracy <= 1f -> SuccessGreen
                    gpsAccuracy <= 3f -> WarningAmber
                    else -> Color(0xFFEF4444)
                }
                Icon(
                    Icons.Filled.GpsFixed,
                    contentDescription = "GPS",
                    tint = accColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "%.1fm".format(gpsAccuracy),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = accColor,
                )
            }
        }

        // Point count (top-right)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Text(
                text = "${points.size} pts",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // Controls (bottom)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!walking) {
                // Start walking button
                FloatingActionButton(
                    onClick = { startWalking() },
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.MyLocation, "Start")
                        Text("Start Walking", style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                // Finish button (only when 3+ points exist)
                FloatingActionButton(
                    onClick = { stopWalking() },
                    containerColor = if (points.size >= 3) NeonLime else SurfaceVariant,
                    contentColor = Color.Black,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Check, "Finish")
                        Text("Finish", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Compute polygon area in hectares using the Shoelace formula
 * with a simple lat/lon to meters approximation.
 */
private fun computeAreaHectares(points: List<LatLon>): Double {
    if (points.size < 3) return 0.0

    val refLat = points[0].lat
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = 111_320.0 * Math.cos(Math.toRadians(refLat))

    // Convert to local meters
    val xs = points.map { (it.lon - points[0].lon) * metersPerDegLon }
    val ys = points.map { (it.lat - points[0].lat) * metersPerDegLat }

    // Shoelace formula
    var area = 0.0
    val n = xs.size
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += xs[i] * ys[j]
        area -= xs[j] * ys[i]
    }
    area = Math.abs(area) / 2.0

    return area / 10_000.0 // m2 to hectares
}
