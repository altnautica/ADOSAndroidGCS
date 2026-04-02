package com.altnautica.gcs.ui.flightlog

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.altnautica.gcs.data.flightlog.FlightSession
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SurfaceVariant
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FlightDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    viewModel: FlightDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    LaunchedEffect(sessionId) {
        viewModel.loadSession(sessionId)
    }

    val session by viewModel.session

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = OnSurfaceMedium,
                )
            }
            Text(
                text = "Flight Details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        val s = session
        if (s == null) {
            // Loading or not found
            Spacer(Modifier.weight(1f))
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy  HH:mm:ss", Locale.getDefault())
                val minutes = s.durationSeconds / 60
                val seconds = s.durationSeconds % 60

                DetailSection("Time") {
                    DetailRow("Start", dateFormat.format(Date(s.startTime)))
                    if (s.endTime > 0) {
                        DetailRow("End", dateFormat.format(Date(s.endTime)))
                    }
                    DetailRow("Duration", "%d:%02d".format(minutes, seconds))
                }

                DetailSection("Flight Stats") {
                    DetailRow("Max Altitude", "%.1f m".format(s.maxAltitude))
                    DetailRow("Max Speed", "%.1f m/s".format(s.maxSpeed))
                    DetailRow("Max Distance", "%.0f m".format(s.maxDistance))
                    DetailRow("Total Distance", "%.0f m".format(s.totalDistance))
                }

                DetailSection("Battery") {
                    val startStr = if (s.batteryStart >= 0) "${s.batteryStart}%" else "--"
                    val endStr = if (s.batteryEnd >= 0) "${s.batteryEnd}%" else "--"
                    val usedStr = if (s.batteryStart >= 0 && s.batteryEnd >= 0) {
                        "${s.batteryStart - s.batteryEnd}%"
                    } else "--"
                    DetailRow("Start", startStr)
                    DetailRow("End", endStr)
                    DetailRow("Used", usedStr)
                }

                if (s.connectionMode.isNotEmpty() || s.vehicleType.isNotEmpty()) {
                    DetailSection("Connection") {
                        if (s.connectionMode.isNotEmpty()) DetailRow("Mode", s.connectionMode)
                        if (s.vehicleType.isNotEmpty()) DetailRow("Vehicle", s.vehicleType)
                    }
                }

                // Tlog share button
                if (s.tlogPath.isNotEmpty()) {
                    val tlogFile = File(s.tlogPath)
                    if (tlogFile.exists()) {
                        Button(
                            onClick = {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    tlogFile,
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Tlog"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share Tlog File")
                        }
                    }
                }

                // Notes editor
                var notes by remember(s.id) { mutableStateOf(s.notes) }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { newNotes ->
                        notes = newNotes
                        viewModel.updateNotes(s.id, newNotes)
                    },
                    label = { Text("Notes") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = OnSurfaceMedium.copy(alpha = 0.3f),
                        focusedLabelColor = ElectricBlue,
                        cursorColor = ElectricBlue,
                    ),
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ElectricBlue,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
