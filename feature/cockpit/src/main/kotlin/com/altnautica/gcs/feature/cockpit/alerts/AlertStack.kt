package com.altnautica.gcs.feature.cockpit.alerts

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.feature.cockpit.R
import kotlinx.coroutines.delay

/** Critical borders flash at 4 Hz for 3 s, then hold steady. */
private const val CRITICAL_FLASH_MS = 3_000L
private const val CRITICAL_HALF_PERIOD_MS = 125L

/** Warning borders pulse at 2 Hz. */
private const val WARNING_HALF_PERIOD_MS = 250L

/**
 * Alert stack under the safety band (CS-7). The border carries motion; the text never
 * flashes; each level has its own icon and label. Reduced motion stops all flashing.
 */
@Composable
fun AlertStack(alerts: List<CockpitAlert>, modifier: Modifier = Modifier, maxVisible: Int = 3) {
    if (alerts.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1), horizontalAlignment = Alignment.CenterHorizontally) {
        for (alert in alerts.take(maxVisible)) AlertRow(alert)
    }
}

@Composable
private fun AlertRow(alert: CockpitAlert) {
    val colors = AdosTheme.colors
    val reduced = rememberReducedMotion()
    val tint = when (alert.level) {
        AlertLevel.Critical -> colors.hudCrit
        AlertLevel.Warning -> colors.hudWarn
        AlertLevel.Advisory -> colors.hudInk2
    }
    var borderAlpha by remember(alert.id) { mutableFloatStateOf(1f) }
    LaunchedEffect(alert.id, alert.level, reduced) {
        borderAlpha = 1f
        if (reduced || alert.level == AlertLevel.Advisory) return@LaunchedEffect
        val half = if (alert.level == AlertLevel.Critical) CRITICAL_HALF_PERIOD_MS else WARNING_HALF_PERIOD_MS
        var on = true
        while (true) {
            if (alert.level == AlertLevel.Critical && SystemClock.elapsedRealtime() - alert.sinceMs >= CRITICAL_FLASH_MS) {
                borderAlpha = 1f
                return@LaunchedEffect
            }
            on = !on
            borderAlpha = if (on) 1f else 0.15f
            delay(half)
        }
    }
    val (icon, levelLabel) = when (alert.level) {
        AlertLevel.Critical -> Icons.Outlined.Report to stringResource(R.string.cockpit_alert_level_critical)
        AlertLevel.Warning -> Icons.Outlined.WarningAmber to stringResource(R.string.cockpit_alert_level_warning)
        AlertLevel.Advisory -> Icons.Outlined.Info to stringResource(R.string.cockpit_alert_level_advisory)
    }
    val title = stringResource(alertTitleRes(alert.id))
    val detail = alert.detail?.let { d -> fcStateDetailRes(d)?.let { stringResource(it) } ?: d }
    Surface(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .border(2.dp, tint.copy(alpha = borderAlpha), RoundedCornerShape(AdosRadius.Lg))
            .semantics {
                liveRegion = if (alert.level == AlertLevel.Critical) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(AdosRadius.Lg),
        color = colors.hudGlassStrong,
        contentColor = colors.hudInk,
    ) {
        Row(Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AdosSize.IconMd))
            Spacer(Modifier.size(AdosSpacing.S2))
            Text(levelLabel, style = MaterialTheme.typography.labelMedium, color = tint)
            Spacer(Modifier.size(AdosSpacing.S2))
            Text(if (detail != null) "$title · $detail" else title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }
}
