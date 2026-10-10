package com.altnautica.gcs.feature.configure.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.feature.configure.R

@Composable
fun commandResultText(result: CommandResult): String = when (result) {
    CommandResult.Accepted -> stringResource(R.string.cfg_result_accepted)
    is CommandResult.Rejected -> stringResource(R.string.cfg_result_rejected, result.reason.ifBlank { result.code.toString() })
    CommandResult.Unsupported -> stringResource(R.string.cfg_result_unsupported)
    CommandResult.Timeout -> stringResource(R.string.cfg_result_timeout)
    is CommandResult.Denied -> stringResource(R.string.cfg_result_denied, result.reason)
    is CommandResult.Queued -> stringResource(R.string.cfg_result_queued)
}
