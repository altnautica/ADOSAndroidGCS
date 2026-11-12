package com.altnautica.gcs.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCard
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.component.node.profileIcon
import com.altnautica.gcs.core.designsystem.component.node.profileTint
import com.altnautica.gcs.core.designsystem.theme.AdosFonts
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.designsystem.R as DesignR

/** Tall enough for the Quick menu button's full touch target. */
private val TopStripHeight = AdosSize.TouchMin
private val TileSize = 56.dp
private val LivenessDot = 12.dp

/** Wordmark on the left; on the right the controller chip (while a controller is in use) and the Quick menu button. */
@Composable
internal fun HomeTopStrip(modifier: Modifier = Modifier, onOpenQuickMenu: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val colors = AdosTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().height(TopStripHeight).padding(horizontal = AdosSpacing.S6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.home_wordmark),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = AdosFonts.SpaceGrotesk),
            color = colors.fgSecondary,
        )
        Spacer(Modifier.weight(1f))
        ControllerChip()
        onOpenQuickMenu?.let { open ->
            val label = stringResource(R.string.home_quick_open)
            Spacer(Modifier.width(AdosSpacing.S2))
            Box(
                Modifier
                    .size(TopStripHeight)
                    .consoleFocus(CircleShape)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = open)
                    .semantics { contentDescription = label }
                    .testTag("home-quick-menu"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Apps, contentDescription = null, tint = colors.fgPrimary, modifier = Modifier.size(AdosSize.IconMd))
            }
        }
        trailing()
    }
}

@Composable
private fun ControllerChip() {
    val ui = LocalControllerUi.current
    if (!ui.active) return
    val colors = AdosTheme.colors
    val pct = ui.batteryPct
    val spoken = if (pct != null) {
        stringResource(R.string.home_controller_battery, pct)
    } else {
        stringResource(R.string.home_controller_battery_unknown)
    }
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SportsEsports, contentDescription = null, tint = colors.fgSecondary, modifier = Modifier.size(AdosSize.IconMd))
        if (pct != null) {
            Spacer(Modifier.width(AdosSpacing.S1))
            Text("$pct%", style = AdosTypography.numeric.small, color = colors.fgSecondary)
        }
    }
}

/** Round vehicle tiles; Cross (or a tap) selects. The last tile adds a vehicle. */
@Composable
internal fun VehicleRow(
    vehicles: List<FleetCard>,
    selected: DeviceId?,
    onSelect: (DeviceId) -> Unit,
    onAddVehicle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().focusRestorer().focusGroup(),
        contentPadding = PaddingValues(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S1),
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(vehicles, key = { it.node.id.value }) { card ->
            VehicleTile(card, card.node.id == selected, onClick = { onSelect(card.node.id) })
        }
        item(key = "add-vehicle") { AddVehicleTile(onAddVehicle) }
    }
}

@Composable
private fun VehicleTile(card: FleetCard, selected: Boolean, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    val node = card.node
    val liveness = stringResource(livenessLabel(node.liveness))
    val spoken = stringResource(
        if (selected) R.string.home_vehicle_tile_selected else R.string.home_vehicle_tile,
        node.name,
        liveness,
    )
    val shape = RoundedCornerShape(AdosRadius.Md)
    Box(Modifier.size(TileSize)) {
        Box(
            Modifier
                .size(TileSize)
                .consoleFocus(shape)
                .clip(shape)
                .background(colors.bgRaised)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.accentPrimary else colors.borderDefault, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = spoken },
            contentAlignment = Alignment.Center,
        ) {
            Icon(profileIcon(node.profile), contentDescription = null, tint = profileTint(node.profile), modifier = Modifier.size(AdosSize.IconLg))
        }
        val dot = when (node.liveness) {
            Liveness.Live -> colors.statusSuccess
            Liveness.Stale -> colors.statusWarning
            Liveness.Offline -> colors.fgTertiary
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(LivenessDot)
                .clip(CircleShape)
                .background(dot)
                .border(2.dp, colors.bgCanvas, CircleShape),
        )
    }
}

@Composable
private fun AddVehicleTile(onClick: () -> Unit) {
    val colors = AdosTheme.colors
    val label = stringResource(R.string.home_add_vehicle)
    Row(
        Modifier
            .height(TileSize)
            .consoleFocus(CircleShape)
            .clip(CircleShape)
            .border(1.dp, colors.borderStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AdosSpacing.S4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = colors.fgPrimary, modifier = Modifier.size(AdosSize.IconMd))
        Spacer(Modifier.width(AdosSpacing.S2))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
    }
}

/** Landscape launch row: Fly leads as the large card. */
@Composable
internal fun SurfaceRow(
    cards: List<SurfaceCard>,
    focusSurface: HomeSurface?,
    focusRequester: FocusRequester,
    onOpen: (HomeSurface) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().focusRestorer().focusGroup(),
        contentPadding = PaddingValues(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S3),
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        // Top-aligned so the taller Fly card never sits "above" its neighbours for D-pad up.
        verticalAlignment = Alignment.Top,
    ) {
        items(cards, key = { it.surface.name }) { card ->
            SurfaceLaunchCard(card, card.surface == focusSurface, focusRequester, onOpen)
        }
    }
}

/** Columns of the portrait launch grid: three, so all seven cards fit above the hint bar on a phone. */
internal const val PORTRAIT_GRID_COLUMNS = 3

/** Portrait launch grid. */
@Composable
internal fun SurfaceGrid(
    cards: List<SurfaceCard>,
    focusSurface: HomeSurface?,
    focusRequester: FocusRequester,
    onOpen: (HomeSurface) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(PORTRAIT_GRID_COLUMNS),
        modifier = modifier.fillMaxWidth().focusRestorer().focusGroup(),
        contentPadding = PaddingValues(AdosSpacing.S4),
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        items(cards, key = { it.surface.name }) { card ->
            SurfaceLaunchCard(card, card.surface == focusSurface, focusRequester, onOpen, emphasis = false)
        }
    }
}

@Composable
private fun SurfaceLaunchCard(
    card: SurfaceCard,
    initialFocus: Boolean,
    focusRequester: FocusRequester,
    onOpen: (HomeSurface) -> Unit,
    emphasis: Boolean = card.surface == HomeSurface.Fly,
) {
    if (emphasis && card.surface == HomeSurface.Fly) {
        FlyCard(
            title = stringResource(card.surface.label),
            subtitle = card.reason?.takeIf { !card.enabled }?.let { stringResource(it) } ?: stringResource(R.string.home_fly_tagline),
            enabled = card.enabled,
            onClick = { onOpen(card.surface) },
            focusRequester = if (initialFocus) focusRequester else null,
        )
        return
    }
    ConsoleCard(
        title = stringResource(card.surface.label),
        icon = card.surface.icon,
        onClick = { onOpen(card.surface) },
        enabled = card.enabled,
        disabledReason = card.reason?.let { stringResource(it) },
        emphasis = emphasis,
        focusRequester = if (initialFocus) focusRequester else null,
    )
}

internal fun livenessLabel(liveness: Liveness): Int = when (liveness) {
    Liveness.Live -> DesignR.string.liveness_live
    Liveness.Stale -> DesignR.string.liveness_stale
    Liveness.Offline -> DesignR.string.liveness_offline
}
