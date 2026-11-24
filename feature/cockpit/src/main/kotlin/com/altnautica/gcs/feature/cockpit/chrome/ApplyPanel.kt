package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.alerts.ApplyFailure
import com.altnautica.gcs.feature.cockpit.alerts.ApplyPhase
import com.altnautica.gcs.feature.cockpit.alerts.ProfileReview
import com.altnautica.gcs.feature.cockpit.alerts.ProfileRow
import com.altnautica.gcs.feature.cockpit.flow.specText

/** The hold that writes the reviewed values, as long as every other setup write hold. */
internal const val APPLY_HOLD_MS = 1_500

/**
 * An alert row's inline "Apply recommended values" past its button: reading, then the review
 * (each changed row as current → new, the rows already set folded, the restart note, the
 * snapshot note, the hold that writes and Cancel), then writing, done or the failure with Open
 * setup.
 */
@Composable
internal fun ApplyPanel(phase: ApplyPhase, onWrite: () -> Unit, onCancel: () -> Unit, onOpenSetup: () -> Unit) {
    val c = LocalChrome.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("alert-apply"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (phase) {
            ApplyPhase.Idle -> Unit
            ApplyPhase.Reading -> Busy(stringResource(R.string.alerts_apply_reading))
            is ApplyPhase.Review -> {
                ProfileReviewTable(phase.review)
                Text(stringResource(R.string.alerts_apply_snapshot), style = c.labelText)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phase.review.actionId != null) {
                        HoldToConfirmButton(
                            label = stringResource(R.string.alerts_apply_hold),
                            holdMs = APPLY_HOLD_MS,
                            onConfirmed = onWrite,
                            modifier = Modifier.testTag("alert-apply-hold"),
                        )
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        border = BorderStroke(1.dp, c.text.copy(alpha = 0.5f)),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("alert-apply-cancel"),
                    ) {
                        Text(stringResource(R.string.alerts_action_cancel), style = c.labelText.copy(color = c.text))
                    }
                }
            }
            ApplyPhase.Writing -> Busy(stringResource(R.string.alerts_apply_writing))
            ApplyPhase.Done -> Text(
                stringResource(R.string.alerts_apply_done),
                style = c.topBarText.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("alert-apply-done"),
            )
            is ApplyPhase.Error -> {
                val text = phase.key?.let { specText(it) } ?: stringResource(
                    when (phase.local) {
                        ApplyFailure.Refused -> R.string.alerts_apply_refused
                        ApplyFailure.Unreachable -> R.string.alerts_apply_unreachable
                        ApplyFailure.Failed -> R.string.alerts_apply_failed
                    },
                )
                Text(
                    text,
                    style = c.topBarText.copy(color = severityColor(StatusSeverity.Critical)),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("alert-apply-error"),
                )
                OutlinedButton(
                    onClick = onOpenSetup,
                    border = BorderStroke(1.dp, c.text.copy(alpha = 0.5f)),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.alerts_action_open_setup), style = c.labelText.copy(color = c.text))
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    val c = LocalChrome.current
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = c.text, strokeWidth = 2.dp)
        Text(text, style = c.labelText, modifier = Modifier.padding(start = 8.dp))
    }
}

/** What the write changes: each changed row as current → new; the rows already set behind a toggle; the restart note. */
@Composable
private fun ProfileReviewTable(review: ProfileReview) {
    val c = LocalChrome.current
    var showSame by remember { mutableStateOf(false) }
    val n = review.changes.size
    Text(
        if (n > 0) pluralStringResource(R.plurals.alerts_apply_changes, n, n) else stringResource(R.string.alerts_apply_nothing),
        style = c.labelText.copy(color = c.text, fontWeight = FontWeight.SemiBold),
    )
    Column(Modifier.testTag("alert-apply-changes")) { review.changes.forEach { ReviewRow(it, dim = false) } }
    if (review.same.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { showSame = !showSame }.testTag("alert-apply-same-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (showSame) "▾" else "▸", style = c.labelText, modifier = Modifier.width(20.dp))
            Text(pluralStringResource(R.plurals.alerts_apply_already_set, review.same.size, review.same.size), style = c.labelText)
        }
        if (showSame) Column { review.same.forEach { ReviewRow(it, dim = true) } }
    }
    if (review.reboots) {
        Text(
            stringResource(R.string.alerts_apply_reboots),
            style = c.labelText.copy(color = severityColor(StatusSeverity.Warning), fontWeight = FontWeight.SemiBold),
            modifier = Modifier.testTag("alert-apply-reboots"),
        )
    }
}

@Composable
private fun ReviewRow(p: ProfileRow, dim: Boolean) {
    val c = LocalChrome.current
    val strong = if (dim) c.textDim else c.text
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, style = c.labelText.copy(color = strong, fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1.4f))
        Text(
            p.have?.let(ProfileReview::number) ?: stringResource(R.string.alerts_param_not_reported),
            style = c.labelText,
            modifier = Modifier.weight(1f),
        )
        Text("→", style = c.labelText, modifier = Modifier.padding(horizontal = 4.dp))
        Text(p.value, style = c.labelText.copy(color = strong, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
    }
}
