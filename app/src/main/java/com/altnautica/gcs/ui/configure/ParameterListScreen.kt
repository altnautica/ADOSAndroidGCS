package com.altnautica.gcs.ui.configure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.mavlink.ParameterManager
import com.altnautica.gcs.data.mavlink.ParameterManager.ParamEntry
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SurfaceVariant

@Composable
fun ParameterListScreen(
    viewModel: ParameterViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val filteredGroups by viewModel.filteredGroups.collectAsStateWithLifecycle()
    val collapsedGroups by viewModel.collapsedGroups.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val params by viewModel.params.collectAsStateWithLifecycle()
    val writeResult by viewModel.writeResult.collectAsStateWithLifecycle()
    val profileResult by viewModel.profileResult.collectAsStateWithLifecycle()

    // Selected param for editing
    var selectedParam by remember { mutableStateOf<ParamEntry?>(null) }

    // Show snackbar for write results
    LaunchedEffect(writeResult) {
        when (val result = writeResult) {
            is WriteResult.Success -> {
                snackbarHostState.showSnackbar(
                    message = "${result.paramName} written",
                    duration = SnackbarDuration.Short,
                )
                viewModel.clearWriteResult()
            }
            is WriteResult.Error -> {
                snackbarHostState.showSnackbar(
                    message = "Failed: ${result.message}",
                    duration = SnackbarDuration.Short,
                )
                viewModel.clearWriteResult()
            }
            null -> {}
        }
    }

    // Show snackbar for profile results
    LaunchedEffect(profileResult) {
        when (val result = profileResult) {
            is ProfileResult.Saved -> {
                snackbarHostState.showSnackbar(
                    message = "Profile saved: ${result.fileName}",
                    duration = SnackbarDuration.Short,
                )
                viewModel.clearProfileResult()
            }
            is ProfileResult.Loaded -> {
                snackbarHostState.showSnackbar(
                    message = "Loaded ${result.count} params from ${result.fileName}",
                    duration = SnackbarDuration.Short,
                )
                viewModel.clearProfileResult()
            }
            is ProfileResult.Error -> {
                snackbarHostState.showSnackbar(
                    message = result.message,
                    duration = SnackbarDuration.Short,
                )
                viewModel.clearProfileResult()
            }
            null -> {}
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("Search parameters...") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = OnSurfaceMedium,
                )
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricBlue,
                cursorColor = ElectricBlue,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Download progress
        val showProgress = downloadStatus == ParameterManager.DownloadStatus.DOWNLOADING
                || (isRefreshing && downloadProgress < 1f)
        if (showProgress) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = ElectricBlue,
                    trackColor = SurfaceVariant,
                )
                Text(
                    text = "Downloading parameters... ${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // Main content
        if (params.isEmpty() && downloadStatus != ParameterManager.DownloadStatus.DOWNLOADING) {
            // Empty state
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Connect to drone to view parameters",
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnSurfaceMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.refreshParams() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh")
                    }
                }
            }
        } else {
            // Param count badge
            if (params.isNotEmpty()) {
                Text(
                    text = "${filteredGroups.sumOf { it.params.size }} params" +
                        if (searchQuery.isNotBlank()) " (filtered from ${params.size})" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Parameter groups
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                filteredGroups.forEach { group ->
                    val isCollapsed = group.prefix in collapsedGroups

                    // Group header
                    item(key = "header_${group.prefix}") {
                        GroupHeader(
                            prefix = group.prefix,
                            count = group.params.size,
                            isCollapsed = isCollapsed,
                            onClick = { viewModel.toggleGroup(group.prefix) },
                        )
                    }

                    // Group items (animated expand/collapse)
                    if (!isCollapsed) {
                        items(
                            items = group.params,
                            key = { "param_${it.name}" },
                        ) { param ->
                            ParamRow(
                                param = param,
                                onClick = { selectedParam = param },
                            )
                        }
                    }
                }
            }
        }

        // Bottom action buttons
        if (params.isNotEmpty()) {
            HorizontalDivider(color = SurfaceVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.refreshParams() },
                    enabled = !isRefreshing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
                OutlinedButton(
                    onClick = { viewModel.saveProfile() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Save Profile")
                }
                OutlinedButton(
                    onClick = { viewModel.loadProfile() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Load Profile")
                }
            }
        }
    }

    // Editor bottom sheet
    selectedParam?.let { param ->
        ParameterEditorSheet(
            param = param,
            onWrite = { name, value -> viewModel.writeParam(name, value) },
            onDismiss = { selectedParam = null },
        )
    }
}

@Composable
private fun GroupHeader(
    prefix: String,
    count: Int,
    isCollapsed: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = SurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = prefix.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ElectricBlue,
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = ElectricBlue.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Icon(
                imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                tint = OnSurfaceMedium,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ParamRow(
    param: ParamEntry,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = param.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatDisplayValue(param.value, param.type),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = NeonLime,
            )
        }
    }
    HorizontalDivider(
        color = SurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

private fun formatDisplayValue(value: Float, type: Int): String {
    return when (type) {
        1, 2, 3, 4, 5, 6, 7, 8 -> value.toInt().toString()
        else -> {
            if (value == value.toLong().toFloat() && value < 1_000_000f) {
                value.toInt().toString()
            } else {
                "%.4f".format(value).trimEnd('0').trimEnd('.')
            }
        }
    }
}
