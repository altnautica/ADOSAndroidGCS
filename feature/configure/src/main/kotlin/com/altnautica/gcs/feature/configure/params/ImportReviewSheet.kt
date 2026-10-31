package com.altnautica.gcs.feature.configure.params

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.configure.R

/**
 * Review of a loaded parameter file: every changed value as a row the operator can untick,
 * the names the vehicle does not have, and Apply for the ticked rows. Apply is refused
 * while armed.
 */
@Composable
internal fun ImportReviewSheet(
    review: ImportReview,
    armed: Boolean,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AdosTheme.colors
    val changed = review.comparison.changed
    AdosSheet(onDismiss = onDismiss, title = stringResource(R.string.cfg_import_title)) {
        Column(Modifier.fillMaxWidth().padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Text(
                stringResource(R.string.cfg_import_summary, changed.size, review.comparison.unknownNames.size),
                color = colors.fgSecondary,
            )
            Text(
                stringResource(R.string.cfg_import_selected, review.selected.size, changed.size),
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgTertiary,
            )
            changed.forEach { d ->
                val checked = d.name !in review.deselected
                AdosListItem(
                    headline = d.name,
                    supporting = "${ParamFile.formatValue(d.current)} → ${ParamFile.formatValue(d.fromFile)}",
                    onClick = { onToggle(d.name) },
                    enabled = !review.running,
                    // The row is the one toggle target; the box only shows the state.
                    leading = { Checkbox(checked = checked, onCheckedChange = null) },
                    trailing = if (d.name in review.failed) {
                        { Text(stringResource(R.string.cfg_param_write_failed, d.name), color = colors.statusError, style = MaterialTheme.typography.labelSmall) }
                    } else {
                        null
                    },
                )
            }
            if (review.comparison.unknownNames.isNotEmpty()) {
                SectionHeader(stringResource(R.string.cfg_import_not_on_vehicle))
                review.comparison.unknownNames.forEach { name ->
                    Text(name, style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary)
                }
            }
            if (review.running || review.applied > 0 || review.failed.isNotEmpty()) {
                Text(stringResource(R.string.cfg_import_progress, review.applied, review.selected.size, review.failed.size))
            }
            if (armed) {
                Text(stringResource(R.string.cfg_import_disarm_first), color = colors.statusWarning)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(
                    stringResource(R.string.cfg_import_apply),
                    onClick = onApply,
                    enabled = !armed && !review.running && review.selected.isNotEmpty(),
                )
                AdosButton(stringResource(R.string.cfg_close), onClick = onDismiss, variant = AdosButtonVariant.Secondary)
            }
        }
    }
}
