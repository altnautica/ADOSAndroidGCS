package com.altnautica.gcs.feature.cockpit.alerts

import android.os.SystemClock
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import com.altnautica.gcs.feature.cockpit.chrome.rememberRedrawBeat
import com.altnautica.gcs.feature.cockpit.flow.specText
import kotlinx.coroutines.flow.takeWhile

/** Critical borders flash at about 4 Hz for 3 s, then hold steady. */
private const val CRITICAL_FLASH_MS = 3_000L
private const val CRITICAL_HALF_PERIOD_MS = 125L

/** Warning borders pulse at 2 Hz. */
private const val WARNING_HALF_PERIOD_MS = 250L

private const val FLASH_DIM_ALPHA = 0.15f

/**
 * Alert stack under the safety band. The border carries motion; the text never
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
    // Stepped on the flight screen's beat and read by the border's draw pass only, so a
    // flashing alert redraws in the beat's frame and never recomposes.
    val borderAlpha = remember(alert.id) { mutableFloatStateOf(1f) }
    val beat = rememberRedrawBeat()
    LaunchedEffect(alert.id, alert.level, reduced, beat) {
        borderAlpha.floatValue = 1f
        if (reduced || alert.level == AlertLevel.Advisory) return@LaunchedEffect
        val half = if (alert.level == AlertLevel.Critical) CRITICAL_HALF_PERIOD_MS else WARNING_HALF_PERIOD_MS
        var start = -1L
        beat.ticks
            .takeWhile { alert.level != AlertLevel.Critical || SystemClock.elapsedRealtime() - alert.sinceMs < CRITICAL_FLASH_MS }
            .collect { n ->
                if (start < 0) start = n
                // Whole beats: each step lasts the beats that fit its half period, dim first.
                val dim = (n - start) * RedrawBeat.BEAT_MS / half % 2 == 0L
                borderAlpha.floatValue = if (dim) FLASH_DIM_ALPHA else 1f
            }
        borderAlpha.floatValue = 1f
    }
    val (icon, levelLabel) = when (alert.level) {
        AlertLevel.Critical -> Icons.Outlined.Report to specText("alert.level.critical")
        AlertLevel.Warning -> Icons.Outlined.WarningAmber to specText("alert.level.warning")
        AlertLevel.Advisory -> Icons.Outlined.Info to specText("alert.level.advisory")
    }
    val title = alertTitle(alert.id)
    val detail = alertDetail(alert.detail)
    // The chrome panel: its radius and no-blur fill; the border carries the level and its motion.
    val c = LocalChrome.current
    val shape = RoundedCornerShape(c.radius)
    Surface(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .flashingBorder(2.dp, tint, shape) { borderAlpha.floatValue }
            .semantics {
                liveRegion = if (alert.level == AlertLevel.Critical) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        shape = shape,
        color = c.panelNoBlur,
        contentColor = c.text,
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

/** A [width] border inside the bounds, over the content, at the [alpha] read by the draw pass. */
private fun Modifier.flashingBorder(width: Dp, color: Color, shape: Shape, alpha: () -> Float): Modifier = drawWithCache {
    val w = width.toPx()
    val outline = shape.createOutline(Size(size.width - w, size.height - w), layoutDirection, this)
    val stroke = Stroke(w)
    onDrawWithContent {
        drawContent()
        translate(w / 2, w / 2) { drawOutline(outline, color, alpha = alpha(), style = stroke) }
    }
}

/** An alert's on-screen title in the spec's words. */
@Composable
fun alertTitle(id: String): String = specText(alertTitleKey(id))

/** An alert's detail: the FC state in the spec's words, any other detail (FC text) verbatim. */
@Composable
fun alertDetail(detail: String?): String? = detail?.let { d -> fcStateDetailKey(d)?.let { specText(it) } ?: d }
