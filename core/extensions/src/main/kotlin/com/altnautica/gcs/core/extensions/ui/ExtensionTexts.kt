package com.altnautica.gcs.core.extensions.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.extensions.R
import com.altnautica.gcs.core.extensions.data.ExtensionFailure
import com.altnautica.gcs.core.extensions.data.ExtensionReach
import com.altnautica.gcs.core.extensions.data.WriteOutcome

@Composable
internal fun failureText(failure: ExtensionFailure): String = when (failure) {
    ExtensionFailure.Unauthorized -> stringResource(R.string.extensions_unauthorized)
    ExtensionFailure.PinRequired -> stringResource(R.string.extensions_pin_required)
    ExtensionFailure.FeatureAbsent -> stringResource(R.string.extensions_unsupported)
    is ExtensionFailure.Network -> stringResource(R.string.extensions_network_error)
    is ExtensionFailure.Refused -> stringResource(R.string.extensions_refused, failure.message.orEmpty())
}

@Composable
internal fun noPathText(reason: String): String =
    if (reason == ExtensionReach.REASON_LAN_OR_GROUND_REQUIRED) {
        stringResource(R.string.extensions_cloud_only)
    } else {
        stringResource(R.string.extensions_no_agent)
    }

/** Words for the agent's install job stage; null for a stage this app does not know. */
internal fun installStageRes(stage: String): Int? = when (stage) {
    "commanded" -> R.string.extensions_stage_commanded
    "downloading" -> R.string.extensions_stage_downloading
    "verifying" -> R.string.extensions_stage_verifying
    "installing" -> R.string.extensions_stage_installing
    "completed" -> R.string.extensions_stage_completed
    "failed" -> R.string.extensions_stage_failed
    "cancelled" -> R.string.extensions_stage_cancelled
    else -> null
}

@Composable
internal fun noticeText(notice: ExtensionNotice): String = when (notice) {
    is ExtensionNotice.Installed -> stringResource(R.string.extensions_installed, notice.name)
    is ExtensionNotice.Uninstalled -> stringResource(R.string.extensions_uninstalled, notice.name)
    is ExtensionNotice.Problem -> when (val o = notice.outcome) {
        is WriteOutcome.NoPath -> noPathText(o.reasonKey)
        is WriteOutcome.Failed -> failureText(o.failure)
        WriteOutcome.Done -> ""
    }
}

/** A manifest label: literal text, or an extension i18n key rendered as readable words. */
internal fun displayLabel(label: String?, fallback: String): String {
    val l = label?.takeIf { it.isNotBlank() } ?: return fallback
    if (l.contains(' ') || !l.contains('.')) return l
    val last = l.substringAfterLast('.')
    val words = last.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').lowercase()
    return words.replaceFirstChar { it.uppercase() }
}
