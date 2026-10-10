package com.altnautica.gcs.feature.cockpit.preflight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.R

/** Label of a check; [known] false appends "not reported". */
@Composable
fun preflightItemLabel(item: PreflightItem, known: Boolean): String {
    val base = stringResource(
        when (item) {
            PreflightItem.Gps3d -> R.string.cockpit_preflight_gps
            PreflightItem.Battery -> R.string.cockpit_preflight_battery
            PreflightItem.Ekf -> R.string.cockpit_preflight_ekf
            PreflightItem.Home -> R.string.cockpit_preflight_home
            PreflightItem.Link -> R.string.cockpit_preflight_link
            PreflightItem.NoPrearm -> R.string.cockpit_preflight_prearm
        },
    )
    return if (known) base else stringResource(R.string.cockpit_preflight_not_reported, base)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreflightSheet(result: PreflightResult, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgSurface, contentColor = colors.fgPrimary) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            Text(
                stringResource(R.string.cockpit_preflight_title, result.passed, result.total),
                style = MaterialTheme.typography.titleMedium,
            )
            for (check in result.checks) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        check.passed -> Icons.Outlined.CheckCircle to colors.statusSuccess
                        !check.known -> Icons.Outlined.HelpOutline to colors.statusWarning
                        else -> Icons.Outlined.Cancel to colors.statusError
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AdosSize.IconMd))
                    Spacer(Modifier.size(AdosSpacing.S2))
                    Text(preflightItemLabel(check.item, check.known), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(if (check.passed) R.string.cockpit_preflight_pass else R.string.cockpit_preflight_fail),
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                    )
                }
            }
        }
    }
}
