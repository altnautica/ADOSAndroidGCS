package com.altnautica.gcs.feature.cockpit.preflight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.flow.specText

/** Label of a check; [known] false appends "not reported". */
@Composable
fun preflightItemLabel(item: PreflightItem, known: Boolean): String {
    val base = when (item) {
        PreflightItem.Gps3d -> stringResource(R.string.cockpit_preflight_gps)
        PreflightItem.Battery -> specText("flow.preflight.battery")
        PreflightItem.Ekf -> stringResource(R.string.cockpit_preflight_ekf)
        PreflightItem.Home -> stringResource(R.string.cockpit_preflight_home)
        PreflightItem.Link -> specText("flow.preflight.link")
        PreflightItem.NoPrearm -> specText("flow.preflight.fc_prearm")
        PreflightItem.NavSource -> specText("flow.preflight.nav_source")
        // A pad, the on-screen sticks or skills alone can fly.
        PreflightItem.Controller -> specText("flow.preflight.controller_optional")
        PreflightItem.Storage -> specText("flow.preflight.storage")
    }
    return if (known) base else stringResource(R.string.cockpit_preflight_not_reported, base)
}

/**
 * The pre-flight checklist: the surface checks, then the pilot's readiness items. When
 * [gating] is true an arm waits on it, and Continue (enabled once nothing blocks) lets it go.
 * Drawn in the cockpit window; place it in a full-size box.
 */
@Composable
fun PreflightSheet(result: PreflightResult, gating: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    AdosSheet(onDismiss, title = specText("flow.preflight.title")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            Text(stringResource(R.string.cockpit_preflight_title, result.passed, result.total), style = MaterialTheme.typography.titleMedium)
            for (check in result.checks) {
                val (icon, tint) = when {
                    check.passed -> Icons.Outlined.CheckCircle to colors.statusSuccess
                    !check.known -> Icons.Outlined.HelpOutline to colors.statusWarning
                    !check.blocking -> Icons.Outlined.WarningAmber to colors.statusWarning
                    else -> Icons.Outlined.Cancel to colors.statusError
                }
                CheckRow(icon, tint, preflightItemLabel(check.item, check.known), check.passed, "$PREFLIGHT_ROW_TAG:${check.item.name}")
            }
            for (item in result.readiness) ReadinessRow(item)
            if (gating) {
                if (!result.ready) {
                    Text(stringResource(R.string.preflight_gate_blocked), style = MaterialTheme.typography.bodySmall, color = colors.statusError)
                }
                AdosButton(
                    stringResource(R.string.preflight_gate_continue),
                    onClick = onContinue,
                    enabled = result.ready,
                    modifier = Modifier.fillMaxWidth().testTag(PREFLIGHT_CONTINUE_TAG),
                )
            }
        }
    }
}

@Composable
private fun ReadinessRow(item: ReadinessItem) {
    val colors = AdosTheme.colors
    val (icon, tint) = when {
        item.ok -> Icons.Outlined.CheckCircle to colors.statusSuccess
        item.severity == ReadinessSeverity.Warn -> Icons.Outlined.WarningAmber to colors.statusWarning
        else -> Icons.Outlined.Cancel to colors.statusError
    }
    CheckRow(icon, tint, item.msg, item.ok, "$PREFLIGHT_ROW_TAG:${item.id}")
}

@Composable
private fun CheckRow(icon: ImageVector, tint: Color, label: String, passed: Boolean, tag: String) {
    Row(Modifier.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AdosSize.IconMd))
        Spacer(Modifier.size(AdosSpacing.S2))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (passed) R.string.cockpit_preflight_pass else R.string.cockpit_preflight_fail),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

const val PREFLIGHT_ROW_TAG = "preflight_row"
const val PREFLIGHT_CONTINUE_TAG = "preflight_continue"
