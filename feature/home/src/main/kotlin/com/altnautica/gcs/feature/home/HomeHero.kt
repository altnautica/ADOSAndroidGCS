package com.altnautica.gcs.feature.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.BatteryUnknown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.EmptyStateAction
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.node.LivenessChip
import com.altnautica.gcs.core.designsystem.component.node.ReachBadge
import com.altnautica.gcs.core.designsystem.component.node.profileIcon
import com.altnautica.gcs.core.designsystem.component.node.profileLabel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private val FaintIconSize = 220.dp

/** Share of the window width the hero text takes in landscape. */
internal const val HERO_WIDTH = 0.55f

/**
 * Hero backdrop: the selected vehicle's live still, cropped and dimmed from the left
 * so the text stays readable, or a large faint profile icon when there is no still.
 */
@Composable
internal fun BoxScope.HeroBackground(card: FleetCard?, thumbnails: (DeviceId) -> Flow<ByteArray?>) {
    val colors = AdosTheme.colors
    Box(Modifier.matchParentSize().background(colors.bgCanvas))
    if (card == null) return
    val bitmap = if (card.showThumbnail) key(card.node.id) { rememberThumbnail(card.node.id, thumbnails) } else null
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.home_thumbnail, card.node.name),
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(colors.mediaScrim, colors.mediaScrim.copy(alpha = 0.5f), Color.Transparent)),
            ),
        )
    } else {
        Icon(
            profileIcon(card.node.profile),
            contentDescription = null,
            tint = colors.fgPrimary,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = AdosSpacing.S8).size(FaintIconSize).alpha(0.05f),
        )
    }
}

/** Latest video still, decoded off the main thread; keeps the last frame while the next one loads. */
@Composable
private fun rememberThumbnail(id: DeviceId, thumbnails: (DeviceId) -> Flow<ByteArray?>): ImageBitmap? {
    val flow = remember(id) { thumbnails(id) }
    val bytes by flow.collectAsStateWithLifecycle(initialValue = null)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes) {
        val data = bytes ?: return@produceState
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
            ?.let { value = it }
    }
    return bitmap
}

/** Name, reaches, vitals and readiness of the selected vehicle. */
@Composable
internal fun HeroInfo(card: FleetCard, fly: SurfaceCard?, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    val node = card.node
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        Text(
            node.name,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.fgPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            Text(stringResource(profileLabel(node.profile)), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            if (node.reaches.isEmpty()) ReachBadge(null) else node.reaches.forEach { ReachBadge(it) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            VitalsChips(card)
            LivenessChip(node.liveness)
        }
        node.carriedDroneId?.let {
            Text(stringResource(R.string.home_carries, it.value), style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        }
        if (fly != null) Readiness(fly)
    }
}

@Composable
private fun VitalsChips(card: FleetCard) {
    val pct = card.vitals.batteryPct
    StatusChip(
        level = StatusLevel.Neutral,
        label = if (pct != null) stringResource(R.string.home_battery, pct) else stringResource(R.string.home_battery_unknown),
        icon = if (pct != null) Icons.Outlined.BatteryStd else Icons.Outlined.BatteryUnknown,
    )
    StatusChip(
        level = StatusLevel.Neutral,
        label = card.vitals.modeName ?: stringResource(R.string.home_mode_unknown),
        icon = Icons.Outlined.NearMe,
    )
    card.vitals.armed?.let { armed ->
        StatusChip(
            level = if (armed) StatusLevel.Warning else StatusLevel.Neutral,
            label = stringResource(if (armed) R.string.home_armed else R.string.home_disarmed),
            icon = if (armed) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
        )
    }
}

@Composable
private fun Readiness(fly: SurfaceCard) {
    val colors = AdosTheme.colors
    val ready = fly.enabled
    val reason = fly.reason
    val label = if (ready || reason == null) stringResource(R.string.home_ready) else stringResource(reason)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Icon(
            if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = if (ready) colors.statusSuccess else colors.statusWarning,
        )
        Text(label, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
    }
}

/**
 * No vehicles yet: add one, or try the demo drone. [compact] (landscape, short
 * height) puts the text left-aligned and both actions on one row so they stay
 * above the surface row.
 */
@Composable
internal fun HeroEmpty(onAddVehicle: () -> Unit, onEnableDemo: () -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    if (compact) {
        val colors = AdosTheme.colors
        Column(
            modifier = modifier.padding(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge, color = colors.fgPrimary)
            Text(stringResource(R.string.home_empty_message), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
                AdosButton(stringResource(R.string.home_try_demo), onClick = onEnableDemo)
                AdosButton(stringResource(R.string.home_add_vehicle), onClick = onAddVehicle, variant = AdosButtonVariant.Secondary)
            }
        }
        return
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            icon = profileIcon(NodeProfile.Drone),
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            action = EmptyStateAction(stringResource(R.string.home_add_vehicle), onAddVehicle),
        )
        AdosButton(
            label = stringResource(R.string.home_try_demo),
            onClick = onEnableDemo,
            variant = AdosButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(0.6f),
        )
    }
}

/** Fills the hero area with [content] in the given orientation's width share. */
@Composable
internal fun HeroArea(landscape: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(if (landscape) HERO_WIDTH else 1f)) { content() }
    }
}
