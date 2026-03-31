package com.altnautica.gcs.ui.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.maps.TileBounds
import com.altnautica.gcs.data.maps.TileDownloadManager
import com.altnautica.gcs.data.maps.TileEstimate
import com.altnautica.gcs.data.maps.TilePack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TileDownloadViewModel @Inject constructor(
    private val tileDownloadManager: TileDownloadManager,
) : ViewModel() {

    val downloading: StateFlow<Boolean> = tileDownloadManager.downloading
    val progress: StateFlow<Float> = tileDownloadManager.progress
    val downloadedPacks: StateFlow<List<TilePack>> = tileDownloadManager.downloadedPacks

    init {
        tileDownloadManager.loadPacks()
    }

    fun estimate(bounds: TileBounds, minZoom: Int, maxZoom: Int): TileEstimate =
        tileDownloadManager.estimateDownload(bounds, minZoom, maxZoom)

    fun startDownload(bounds: TileBounds, minZoom: Int, maxZoom: Int) {
        viewModelScope.launch {
            tileDownloadManager.startDownload(bounds, minZoom, maxZoom) {}
        }
    }

    fun cancel() = tileDownloadManager.cancelDownload()

    fun deletePack(id: String) = tileDownloadManager.deletePack(id)
}

@Composable
fun TileDownloadScreen(
    viewModel: TileDownloadViewModel = hiltViewModel(),
) {
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val packs by viewModel.downloadedPacks.collectAsStateWithLifecycle()

    // Default bounds (Bangalore area for demo)
    var minZoom by remember { mutableIntStateOf(10) }
    var maxZoom by remember { mutableIntStateOf(15) }
    val defaultBounds = remember {
        TileBounds(
            minLat = 12.85,
            maxLat = 13.05,
            minLon = 77.50,
            maxLon = 77.70,
        )
    }

    var estimatedTiles by remember { mutableFloatStateOf(0f) }
    var estimatedSizeMb by remember { mutableFloatStateOf(0f) }

    // Update estimate when zoom changes
    LaunchedEffect(minZoom, maxZoom) {
        val est = viewModel.estimate(defaultBounds, minZoom, maxZoom)
        estimatedTiles = est.tileCount.toFloat()
        estimatedSizeMb = est.estimatedSizeMb
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Offline Maps",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        // Zoom range selector
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Download Area",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Set bounding box on map, then choose zoom levels",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Min Zoom: $minZoom",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = minZoom.toFloat(),
                    onValueChange = { minZoom = it.toInt() },
                    valueRange = 1f..18f,
                    steps = 16,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricBlue,
                        activeTrackColor = ElectricBlue,
                    ),
                )

                Text(
                    text = "Max Zoom: $maxZoom",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = maxZoom.toFloat(),
                    onValueChange = { maxZoom = it.toInt().coerceAtLeast(minZoom) },
                    valueRange = 1f..18f,
                    steps = 16,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricBlue,
                        activeTrackColor = ElectricBlue,
                    ),
                )

                Spacer(Modifier.height(4.dp))

                // Estimate display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Tiles: ${estimatedTiles.toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Est. size: ${"%.1f".format(estimatedSizeMb)} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Download / Cancel button
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = ElectricBlue,
                        trackColor = ElectricBlue.copy(alpha = 0.15f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${"%.0f".format(progress * 100)}% downloaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.cancel() },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel")
                    }
                } else {
                    Button(
                        onClick = { viewModel.startDownload(defaultBounds, minZoom, maxZoom) },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download Tiles")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Downloaded packs list
        Text(
            text = "Downloaded Packs (${packs.size})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))

        if (packs.isEmpty()) {
            Text(
                text = "No offline tiles downloaded yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(packs, key = { it.id }) { pack ->
                    TilePackRow(
                        pack = pack,
                        onDelete = { viewModel.deletePack(pack.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TilePackRow(
    pack: TilePack,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${pack.tileCount} tiles, ${"%.1f".format(pack.sizeMb)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = ErrorRed)
            }
        }
    }
}
