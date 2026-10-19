package com.altnautica.gcs.feature.node.visor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.PageProblem
import kotlinx.coroutines.delay

internal fun ChipLevel.status(): StatusLevel = when (this) {
    ChipLevel.Ok -> StatusLevel.Success
    ChipLevel.Warn -> StatusLevel.Warning
    ChipLevel.Bad -> StatusLevel.Error
}

@Composable
internal fun healthLabel(id: String): String = stringResource(
    when (id) {
        "blur" -> R.string.visor_health_blur
        "texture" -> R.string.visor_health_texture
        "flicker" -> R.string.visor_health_flicker
        "corridor" -> R.string.visor_health_corridor
        "storage" -> R.string.visor_health_storage
        else -> R.string.visor_health_clock
    },
)

@Composable
internal fun actionLabel(action: CaptureAction): String = stringResource(
    when (action) {
        CaptureAction.Start -> R.string.visor_action_start
        CaptureAction.Stop -> R.string.visor_action_stop
        CaptureAction.Pause -> R.string.visor_action_pause
        CaptureAction.Resume -> R.string.visor_action_resume
        CaptureAction.Mark -> R.string.visor_action_mark
        CaptureAction.Exposure -> R.string.visor_action_exposure
        CaptureAction.Upload -> R.string.visor_action_upload
        CaptureAction.Reconstruct -> R.string.visor_action_reconstruct
        CaptureAction.Delete -> R.string.visor_action_delete
    },
)

@Composable
internal fun problemText(problem: CaptureProblem): String = when (problem) {
    CaptureProblem.StorageFull -> stringResource(R.string.visor_problem_storage_full)
    CaptureProblem.AlreadyRecording -> stringResource(R.string.visor_problem_already_recording)
    CaptureProblem.NotUploaded -> stringResource(R.string.visor_problem_not_uploaded)
    CaptureProblem.Armed -> stringResource(R.string.node_write_armed)
    is CaptureProblem.Other -> problem.message ?: stringResource(R.string.node_write_failed)
    is CaptureProblem.Page -> when (val p = problem.problem) {
        PageProblem.NoAgentLink -> stringResource(R.string.node_problem_no_link_message)
        PageProblem.Unauthorized -> stringResource(R.string.node_problem_unauthorized)
        is PageProblem.PinRequired -> p.message ?: stringResource(R.string.node_problem_pin)
        PageProblem.Unsupported -> stringResource(R.string.visor_problem_no_extension)
        is PageProblem.Unreachable -> p.message ?: stringResource(R.string.node_problem_unreachable)
    }
}

/** Feedback for the last capture or session action. */
@Composable
internal fun ActionFeedback(state: ActionState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        ActionState.Idle -> Unit
        is ActionState.Running -> StatusChip(StatusLevel.Info, stringResource(R.string.visor_action_running, actionLabel(state.action)), modifier)
        is ActionState.Done -> {
            val label = actionLabel(state.action)
            StatusChip(
                StatusLevel.Success,
                state.detail?.let { stringResource(R.string.visor_action_done_detail, label, it) } ?: stringResource(R.string.visor_action_done, label),
                modifier,
            )
            LaunchedEffect(state) {
                delay(FEEDBACK_MS)
                onDismiss()
            }
        }
        is ActionState.Failed -> StatusChip(
            StatusLevel.Error,
            stringResource(R.string.node_write_failed_label, actionLabel(state.action), problemText(state.problem)),
            modifier,
        )
    }
}

internal fun formatDuration(seconds: Double?): String? = seconds?.let {
    val s = it.toLong().coerceAtLeast(0)
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

internal fun formatBytes(bytes: Long?): String? = bytes?.let {
    val gib = it / GIB
    if (gib >= 1.0) "%.1f GiB".format(gib) else "%.0f MiB".format(it / MIB)
}

private const val GIB = 1024.0 * 1024.0 * 1024.0
private const val MIB = 1024.0 * 1024.0
private const val FEEDBACK_MS = 2_500L
