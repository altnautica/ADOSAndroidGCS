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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
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
 * the names the vehicle does not have, and Apply for the ticked rows. A row the single-edit
 * checks refuse stays unticked with the reason. Apply is refused while armed; closing while
 * it runs stops it.
 */
@Composable
internal fun ImportReviewSheet(
    review: ImportReview,
    armed: Boolean,
    metaFor: (String) -> ParamMeta?,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AdosTheme.colors
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        val n = review.selected.size
        AdosConfirmSheet(
            title = pluralStringResource(R.plurals.cfg_import_confirm_title, n, n),
            message = stringResource(R.string.cfg_import_confirm_message),
            onDismiss = { confirming = false },
        ) {
            AdosButton(
                stringResource(R.string.cfg_import_confirm),
                onClick = {
                    confirming = false
                    onApply()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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
                val refused = review.invalid[d.name]
                val checked = refused == null && d.name !in review.deselected
                val note = when {
                    refused != null -> invalidReasonText(refused, metaFor(d.name)) to colors.statusWarning
                    d.name in review.failed -> stringResource(R.string.cfg_param_write_failed, d.name) to colors.statusError
                    else -> null
                }
                AdosListItem(
                    headline = d.name,
                    supporting = "${ParamFile.displayValue(d.current)} → ${ParamFile.displayValue(d.fromFile)}",
                    onClick = { onToggle(d.name) },
                    enabled = !review.running && refused == null,
                    // The row is the one toggle target; the box only shows the state.
                    leading = { Checkbox(checked = checked, onCheckedChange = null) },
                    trailing = note?.let { (text, color) -> @Composable { Text(text, color = color, style = MaterialTheme.typography.labelSmall) } },
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
                    pluralStringResource(R.plurals.cfg_import_apply, review.selected.size, review.selected.size),
                    onClick = { confirming = true },
                    enabled = !armed && !review.running && review.selected.isNotEmpty(),
                )
                AdosButton(stringResource(R.string.cfg_close), onClick = onDismiss, variant = AdosButtonVariant.Secondary)
            }
        }
    }
}
