package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** Asks before an action drops unsaved edits; Discard is a tap-tier danger button. */
@Composable
internal fun DiscardConfirmSheet(state: PlannerUiState, actions: PlannerActions) {
    if (state.pendingDiscard == null) return
    AdosConfirmSheet(
        title = stringResource(R.string.plan_discard_title),
        message = stringResource(R.string.plan_discard_message),
        onDismiss = actions.onCancelDiscard,
        danger = true,
    ) {
        ConfirmButtons(
            confirm = stringResource(R.string.plan_discard_confirm),
            onConfirm = actions.onConfirmDiscard,
            onCancel = actions.onCancelDiscard,
        )
    }
}

/** Asks before a stored plan is deleted from the library. */
@Composable
internal fun DeleteSavedConfirmSheet(state: PlannerUiState, actions: PlannerActions) {
    val entity = state.pendingDelete ?: return
    AdosConfirmSheet(
        title = stringResource(R.string.plan_delete_saved_title, entity.name),
        message = stringResource(R.string.plan_delete_saved_message),
        onDismiss = actions.onCancelDeleteSaved,
        danger = true,
    ) {
        ConfirmButtons(
            confirm = stringResource(R.string.plan_delete_saved_confirm),
            onConfirm = actions.onConfirmDeleteSaved,
            onCancel = actions.onCancelDeleteSaved,
        )
    }
}

@Composable
private fun ConfirmButtons(confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        AdosButton(stringResource(R.string.plan_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary, modifier = Modifier.weight(1f))
        AdosButton(confirm, onClick = onConfirm, variant = AdosButtonVariant.Danger, modifier = Modifier.weight(1f))
    }
}
