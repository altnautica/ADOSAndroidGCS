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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.SatelliteAlt
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
import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.NodeProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private val FaintIconSize = 220.dp

/** Share of the window width the hero text takes in landscape. */
internal const val HERO_WIDTH = 0.55f

/**
 * Hero backdrop: the selected vehicle's live still, cropped and dimmed from the left
 * so the text stays readable, or the Home stage (sky, horizon glow and a drifting ground
 * grid) with a faint profile mark when there is no still.
 */
@Composable
internal fun BoxScope.HeroBackground(card: FleetCard?, thumbnails: (DeviceId) -> Flow<ByteArray?>) {
    val colors = AdosTheme.colors
    Box(Modifier.matchParentSize().background(colors.bgCanvas))
    val bitmap = if (card?.showThumbnail == true) key(card.node.id) { rememberThumbnail(card.node.id, thumbnails) } else null
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.home_thumbnail, card!!.node.name),
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(colors.mediaScrim, colors.mediaScrim.copy(alpha = 0.5f), Color.Transparent)),
            ),
        )
    } else {
        StageBackdrop(Modifier.matchParentSize())
        if (card != null) {
            Icon(
                profileIcon(card.node.profile),
                contentDescription = null,
                tint = colors.fgPrimary,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = AdosSpacing.S8).size(FaintIconSize).alpha(0.07f),
            )
        }
    }
}

/**
 * Latest video still, decoded off the main thread; keeps the last frame while the next one
 * decodes, and drops it when the agent reports no frame, so a stopped video never reads as live.
 */
@Composable
private fun rememberThumbnail(id: DeviceId, thumbnails: (DeviceId) -> Flow<ByteArray?>): ImageBitmap? {
    val flow = remember(id) { thumbnails(id) }
    val bytes by flow.collectAsStateWithLifecycle(initialValue = null)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes) {
        val data = bytes
        value = nextThumbnail(value, data) { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    }
    return bitmap
}

/** No frame clears the still; a frame that does not decode keeps the previous one. */
internal suspend fun <T : Any> nextThumbnail(current: T?, bytes: ByteArray?, decode: suspend (ByteArray) -> T?): T? =
    if (bytes == null) null else decode(bytes) ?: current

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
            // A closed direct link with nothing reported yet has no vitals: dash chips would read as "waiting".
            if (!(card.directLinkClosed && card.vitals == NodeVitals.UNKNOWN)) VitalsChips(card)
            // Kept vitals are the last reading before the link closed: say how old it is.
            card.keptHeardAtMs?.let { heard ->
                StatusChip(level = StatusLevel.Warning, label = keptAgeLabel(heard), icon = Icons.Outlined.History)
            }
            LivenessChip(node.liveness)
        }
        node.carriedDroneId?.let {
            Text(stringResource(R.string.home_carries, it.value), style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        }
        if (fly != null) Readiness(heroReadiness(fly, card.vitals, linkClosed = card.directLinkClosed, kept = card.keptHeardAtMs != null))
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
    GpsChip(card.vitals.gpsFix, card.vitals.satellites)
}

/** GPS fix and satellites; a fix below 3D reads as a warning, unknown as a dash. */
@Composable
private fun GpsChip(fix: GpsFix?, satellites: Int?) {
    val fixText = when (fix) {
        null -> null
        GpsFix.NoGps -> null
        GpsFix.NoFix -> stringResource(R.string.home_gps_fix, "—")
        GpsFix.Fix2D -> stringResource(R.string.home_gps_fix_2d)
        GpsFix.Fix3D -> stringResource(R.string.home_gps_fix_3d)
        GpsFix.DGps -> stringResource(R.string.home_gps_fix_dgps)
        GpsFix.RtkFloat -> stringResource(R.string.home_gps_fix_rtk_float)
        GpsFix.RtkFixed -> stringResource(R.string.home_gps_fix_rtk_fixed)
    }
    val label = when {
        fix == null -> stringResource(R.string.home_gps_unknown)
        fix == GpsFix.NoGps -> stringResource(R.string.home_gps_none)
        fix == GpsFix.NoFix -> fixText!!
        satellites != null -> stringResource(R.string.home_gps, fixText!!, satellites)
        else -> stringResource(R.string.home_gps_fix, fixText!!)
    }
    val good = fix != null && fix >= GpsFix.Fix3D
    StatusChip(
        level = when {
            fix == null -> StatusLevel.Neutral
            good -> StatusLevel.Success
            else -> StatusLevel.Warning
        },
        label = label,
        icon = Icons.Outlined.SatelliteAlt,
    )
}

@Composable
private fun Readiness(readiness: HeroReadiness) {
    val colors = AdosTheme.colors
    val label = when (readiness) {
        HeroReadiness.Ready -> stringResource(R.string.home_ready)
        HeroReadiness.Connecting -> stringResource(R.string.home_connecting)
        HeroReadiness.OpensOnFly -> stringResource(R.string.home_opens_on_fly)
        is HeroReadiness.Blocked -> stringResource(readiness.reason)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Icon(
            when (readiness) {
                HeroReadiness.Ready -> Icons.Outlined.CheckCircle
                HeroReadiness.Connecting -> Icons.Outlined.HourglassEmpty
                HeroReadiness.OpensOnFly -> Icons.Outlined.Link
                is HeroReadiness.Blocked -> Icons.Outlined.WarningAmber
            },
            contentDescription = null,
            tint = when (readiness) {
                HeroReadiness.Ready -> colors.statusSuccess
                HeroReadiness.Connecting, HeroReadiness.OpensOnFly -> colors.fgSecondary
                is HeroReadiness.Blocked -> colors.statusWarning
            },
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
