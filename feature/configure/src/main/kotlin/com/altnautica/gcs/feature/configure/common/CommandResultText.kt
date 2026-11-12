package com.altnautica.gcs.feature.configure.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.altnautica.gcs.core.link.text.linkReasonOrMessage
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.feature.configure.R

@Composable
fun commandResultText(result: CommandResult): String = commandResultText(LocalContext.current.resources, result)

/** Operator words for a command result; link reason tokens become sentences, never raw codes. */
fun commandResultText(resources: Resources, result: CommandResult): String = when (result) {
    CommandResult.Accepted -> resources.getString(R.string.cfg_result_accepted)
    is CommandResult.Rejected -> resources.getString(R.string.cfg_result_rejected, linkReasonOrMessage(resources, result.reason))
    CommandResult.Unsupported -> resources.getString(R.string.cfg_result_unsupported)
    CommandResult.Timeout -> resources.getString(R.string.cfg_result_timeout)
    is CommandResult.Denied -> resources.getString(R.string.cfg_result_denied, linkReasonOrMessage(resources, result.reason))
    is CommandResult.Queued -> resources.getString(R.string.cfg_result_queued)
}
