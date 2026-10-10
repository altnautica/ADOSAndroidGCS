package com.altnautica.gcs.feature.fleet

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryUnknown
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.EmptyStateAction
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.fleet.ui.LivenessChip
import com.altnautica.gcs.feature.fleet.ui.ReachBadge
import com.altnautica.gcs.feature.fleet.ui.profileIcon
import com.altnautica.gcs.feature.fleet.ui.profileLabel
import kotlinx.coroutines.flow.Flow

@Composable
fun FleetRoute(
    onConnect: () -> Unit,
    onOpenNode: (DeviceId) -> Unit,
    onFly: (DeviceId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FleetViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedNodesUnreadable by viewModel.savedNodesUnreadable.collectAsStateWithLifecycle()
    FleetScreen(
        state = state,
        savedNodesUnreadable = savedNodesUnreadable,
        thumbnails = viewModel::thumbnails,
        onConnect = onConnect,
        onOpenNode = onOpenNode,
        onFly = onFly,
        onRetrySavedNodes = viewModel::retrySavedNodes,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetScreen(
    state: FleetUiState,
    savedNodesUnreadable: Boolean,
    thumbnails: (DeviceId) -> Flow<ByteArray?>,
    onConnect: () -> Unit,
    onOpenNode: (DeviceId) -> Unit,
    onFly: (DeviceId) -> Unit,
    onRetrySavedNodes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fleet_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (state is FleetUiState.Nodes) {
                ExtendedFloatingActionButton(
                    onClick = onConnect,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.fleet_connect)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val savedNodesError: @Composable (Modifier) -> Unit = { m ->
                ErrorState(
                    reason = stringResource(R.string.fleet_saved_nodes_unreadable),
                    retryLabel = stringResource(R.string.fleet_retry),
                    onRetry = onRetrySavedNodes,
                    modifier = m,
                )
            }
            when (state) {
                FleetUiState.Loading -> LoadingState(stringResource(R.string.fleet_loading), Modifier.align(Alignment.Center))
                // No node listed because the saved ones cannot be read is not "no nodes".
                FleetUiState.Empty -> if (savedNodesUnreadable) {
                    savedNodesError(Modifier.align(Alignment.Center))
                } else {
                    EmptyState(
                        icon = Icons.Outlined.Hub,
                        title = stringResource(R.string.fleet_empty_title),
                        message = stringResource(R.string.fleet_empty_message),
                        action = EmptyStateAction(stringResource(R.string.fleet_connect), onConnect),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                is FleetUiState.Nodes -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(AdosSpacing.S4),
                    horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
                    verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
                ) {
                    if (savedNodesUnreadable) {
                        item(key = "saved-nodes-unreadable", span = { GridItemSpan(maxLineSpan) }) { savedNodesError(Modifier) }
                    }
                    items(state.cards, key = { it.node.id.value }) { card ->
                        NodeCard(card, thumbnails, onOpenNode, onFly)
                    }
                    // Room for the floating Connect button below the last row.
                    item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.size(72.dp)) }
                }
            }
        }
    }
}

@Composable
private fun NodeCard(
    card: FleetCard,
    thumbnails: (DeviceId) -> Flow<ByteArray?>,
    onOpenNode: (DeviceId) -> Unit,
    onFly: (DeviceId) -> Unit,
) {
    val node = card.node
    val colors = AdosTheme.colors
    Surface(
        shape = RoundedCornerShape(AdosRadius.Xl),
        color = colors.bgSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderDefault),
        modifier = Modifier.fillMaxWidth().clickable { onOpenNode(node.id) },
    ) {
        Column(Modifier.padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            if (card.showThumbnail) Thumbnail(node.id, node.name, thumbnails)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(profileIcon(node.profile), contentDescription = null, tint = colors.accentPrimary, modifier = Modifier.size(AdosSize.IconLg))
                Column(Modifier.weight(1f).padding(start = AdosSpacing.S3)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(profileLabel(node.profile)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgSecondary,
                    )
                }
                LivenessChip(node.liveness)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                ReachBadge(node.reaches.firstOrNull())
                val pct = card.vitals.batteryPct
                StatusChip(
                    level = StatusLevel.Neutral,
                    label = if (pct != null) stringResource(R.string.fleet_battery, pct) else stringResource(R.string.fleet_battery_unknown),
                    icon = if (pct != null) Icons.Outlined.BatteryStd else Icons.Outlined.BatteryUnknown,
                )
                StatusChip(
                    level = StatusLevel.Neutral,
                    label = card.vitals.modeName ?: stringResource(R.string.fleet_mode_unknown),
                    icon = Icons.Outlined.NearMe,
                )
                card.vitals.armed?.let { armed ->
                    StatusChip(
                        level = if (armed) StatusLevel.Warning else StatusLevel.Neutral,
                        label = stringResource(if (armed) R.string.fleet_armed else R.string.fleet_disarmed),
                        icon = if (armed) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    )
                }
            }
            node.carriedDroneId?.let {
                Text(stringResource(R.string.fleet_carries, it.value), style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(
                    label = stringResource(R.string.fleet_open),
                    onClick = { onOpenNode(node.id) },
                    variant = AdosButtonVariant.Secondary,
                    icon = Icons.Outlined.Info,
                    modifier = Modifier.weight(1f),
                )
                if (card.canFly) {
                    AdosButton(
                        label = stringResource(R.string.fleet_fly),
                        onClick = { onFly(node.id) },
                        icon = Icons.Outlined.SportsEsports,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(id: DeviceId, name: String, thumbnails: (DeviceId) -> Flow<ByteArray?>) {
    val flow = remember(id) { thumbnails(id) }
    val bytes by flow.collectAsStateWithLifecycle(initialValue = null)
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.fleet_thumbnail, name),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(AdosRadius.Lg)),
        )
    }
}
