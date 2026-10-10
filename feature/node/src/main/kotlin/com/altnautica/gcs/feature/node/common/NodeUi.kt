package com.altnautica.gcs.feature.node.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExtensionOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.designsystem.component.ConfirmSheet
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.R
import kotlinx.coroutines.delay

/** Placeholder text for an unknown value (CS-9: never zero). */
const val DASH = "—"

/** CS-9 state for a page that cannot read its agent. */
@Composable
fun ProblemState(problem: PageProblem, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (problem) {
        PageProblem.NoAgentLink -> EmptyState(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.node_problem_no_link_title),
            message = stringResource(R.string.node_problem_no_link_message),
            modifier = modifier,
        )
        PageProblem.Unauthorized -> EmptyState(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.node_problem_denied_title),
            message = stringResource(R.string.node_problem_unauthorized),
            modifier = modifier,
        )
        is PageProblem.PinRequired -> EmptyState(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.node_problem_denied_title),
            message = problem.message ?: stringResource(R.string.node_problem_pin),
            modifier = modifier,
        )
        PageProblem.Unsupported -> EmptyState(
            icon = Icons.Outlined.ExtensionOff,
            title = stringResource(R.string.node_problem_unsupported_title),
            message = stringResource(R.string.node_problem_unsupported_message),
            modifier = modifier,
        )
        is PageProblem.Unreachable -> ErrorState(
            reason = problem.message ?: stringResource(R.string.node_problem_unreachable),
            retryLabel = stringResource(R.string.node_retry),
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

/** Human text for an agent error after a write. */
@Composable
fun agentErrorText(error: AgentError?, message: String?): String = when (error) {
    null -> message ?: stringResource(R.string.node_write_failed)
    AgentError.Unauthorized -> stringResource(R.string.node_problem_unauthorized)
    is AgentError.PinRequired -> error.message ?: stringResource(R.string.node_problem_pin)
    is AgentError.Armed -> stringResource(R.string.node_write_armed)
    AgentError.ProfileMismatch, AgentError.FeatureAbsent -> stringResource(R.string.node_problem_unsupported_message)
    is AgentError.RelayTicket -> error.reason ?: stringResource(R.string.node_problem_unreachable)
    is AgentError.PeerApi -> error.message ?: stringResource(R.string.node_problem_unreachable)
    is AgentError.Network -> stringResource(R.string.node_problem_unreachable)
    is AgentError.Server -> error.message ?: stringResource(R.string.node_write_failed_code, error.code)
}

/**
 * Write feedback plus the `E_ARMED` force-confirm sheet: a write refused
 * because the vehicle is armed asks for an 800 ms hold before it is retried
 * with the override.
 */
@Composable
fun WriteStatus(state: WriteState, runner: AgentWriteRunner, modifier: Modifier = Modifier) {
    when (state) {
        is WriteState.ForceRequired -> ConfirmSheet(
            title = stringResource(R.string.node_force_title),
            message = stringResource(R.string.node_force_message, state.label),
            onDismiss = runner::cancelForce,
            danger = true,
        ) {
            HoldToConfirmButton(
                label = stringResource(R.string.node_force_hold),
                holdMs = FORCE_HOLD_MS,
                onConfirmed = runner::confirmForce,
                danger = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is WriteState.Running -> StatusChip(StatusLevel.Info, stringResource(R.string.node_write_running, state.label), modifier = modifier)
        is WriteState.Done -> {
            StatusChip(StatusLevel.Success, stringResource(R.string.node_write_done, state.label), modifier = modifier)
            LaunchedEffect(state) {
                delay(FEEDBACK_MS)
                runner.dismiss()
            }
        }
        is WriteState.Failed -> StatusChip(
            StatusLevel.Error,
            stringResource(R.string.node_write_failed_label, state.label, agentErrorText(state.error, state.message)),
            modifier = modifier,
        )
        WriteState.Idle -> Unit
    }
}

private const val FORCE_HOLD_MS = 800
private const val FEEDBACK_MS = 2_500L

/** Bordered card holding one group of rows. */
@Composable
fun NodeCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = AdosTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AdosRadius.Xl),
        color = colors.bgSurface,
        border = BorderStroke(1.dp, colors.borderDefault),
    ) {
        Column(Modifier.padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = colors.accentPrimary)
            content()
        }
    }
}

/** Label on the left, value on the right; values use tabular monospace digits. */
@Composable
fun KeyValueRow(label: String, value: String?, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary, modifier = Modifier.weight(1f))
        Text(
            value ?: DASH,
            style = AdosTypography.numeric.medium,
            color = if (value == null) colors.fgTertiary else colors.fgPrimary,
        )
    }
}

fun Double?.fmt(digits: Int = 1, suffix: String = ""): String? = this?.let { "%.${digits}f".format(it) + suffix }

fun Number?.fmtInt(suffix: String = ""): String? = this?.let { "$it$suffix" }
