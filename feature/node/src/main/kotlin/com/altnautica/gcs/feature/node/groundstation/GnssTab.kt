package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.agent.sensors.BaseModeRequest
import com.altnautica.gcs.core.agent.sensors.GnssBase
import com.altnautica.gcs.core.agent.sensors.GnssReceiver
import com.altnautica.gcs.core.agent.sensors.GnssStatus
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.fmtInt

/** GNSS tab body: fix, sats, PPS lock per receiver; RTK base mode with survey-in; RTCM out; heading. */
@Composable
internal fun GnssView(gnss: GnssStatus, busy: Boolean, onBase: (BaseModeRequest) -> Unit) {
    if (!gnss.present) {
        EmptyState(
            icon = Icons.Outlined.SatelliteAlt,
            title = stringResource(R.string.gs_gnss_absent_title),
            message = stringResource(R.string.gs_gnss_absent_message),
        )
        return
    }
    gnss.receivers.forEach { ReceiverCard(it) }
    BaseCard(gnss.base, gnss.receivers.firstOrNull(), busy, onBase)
    gnss.heading?.takeIf { it.available }?.let { h ->
        NodeCard(stringResource(R.string.gs_gnss_heading)) {
            KeyValueRow(stringResource(R.string.gs_gnss_heading_deg), h.deg.fmt(1, "°"))
            KeyValueRow(stringResource(R.string.gs_gnss_heading_accuracy), h.accuracyDeg.fmt(1, "°"))
            KeyValueRow(stringResource(R.string.gs_gnss_baseline), h.baselineM.fmt(2, " m"))
        }
    }
}

@Composable
private fun ReceiverCard(r: GnssReceiver) {
    NodeCard(listOfNotNull(r.id, r.model).joinToString(" · ")) {
        val fixed = r.fix == "rtk_fixed" || r.fix == "3d" || r.fix == "dgps" || r.fix == "rtk_float"
        StatusChip(if (fixed) StatusLevel.Success else StatusLevel.Warning, fixLabel(r.fix))
        KeyValueRow(stringResource(R.string.gs_gnss_sats), r.sats.fmtInt())
        KeyValueRow(stringResource(R.string.gs_gnss_hdop), r.hdop.fmt(1))
        KeyValueRow(stringResource(R.string.gs_gnss_rtk), r.rtk)
        KeyValueRow(
            stringResource(R.string.gs_gnss_position),
            r.latDeg?.let { lat -> r.lonDeg?.let { lon -> "%.6f %.6f".format(lat, lon) } },
        )
        KeyValueRow(stringResource(R.string.gs_gnss_height), r.hEllipsoidM.fmt(1, " m"))
        r.ppsLocked?.let {
            StatusChip(
                if (it) StatusLevel.Success else StatusLevel.Warning,
                stringResource(if (it) R.string.gs_gnss_pps_locked else R.string.gs_gnss_pps_unlocked),
            )
        }
        KeyValueRow(stringResource(R.string.gs_gnss_source), r.source)
    }
}

@Composable
private fun BaseCard(base: GnssBase?, receiver: GnssReceiver?, busy: Boolean, onBase: (BaseModeRequest) -> Unit) {
    var confirmFixed by rememberSaveable { mutableStateOf(false) }
    NodeCard(stringResource(R.string.gs_gnss_base)) {
        val (level, label) = when (base?.state) {
            "surveying" -> StatusLevel.Info to R.string.gs_gnss_base_surveying
            "fixed" -> StatusLevel.Success to R.string.gs_gnss_base_fixed
            "failed" -> StatusLevel.Error to R.string.gs_gnss_base_failed
            "off" -> StatusLevel.Neutral to R.string.gs_gnss_base_off
            else -> StatusLevel.Neutral to R.string.gs_gnss_base_unknown
        }
        StatusChip(level, stringResource(label))
        if (base?.state == "surveying") {
            KeyValueRow(
                stringResource(R.string.gs_gnss_survey_elapsed),
                base.elapsedS?.let { e -> base.minDurS?.let { m -> "$e / $m s" } ?: "$e s" },
            )
            KeyValueRow(
                stringResource(R.string.gs_gnss_survey_accuracy),
                base.accuracyM?.let { a -> base.targetAccuracyM?.let { t -> "%.1f / %.1f m".format(a, t) } ?: "%.1f m".format(a) },
            )
        }
        base?.fixed?.let { KeyValueRow(stringResource(R.string.gs_gnss_base_position), "%.6f %.6f %.1f m".format(it.latDeg, it.lonDeg, it.hEllipsoidM)) }
        base?.rtcmOut?.let { out ->
            KeyValueRow(stringResource(R.string.gs_gnss_rtcm_rate), out.msgsPerS.fmt(1, " msg/s"))
            KeyValueRow(stringResource(R.string.gs_gnss_rtcm_targets), out.targets.fmtInt())
            KeyValueRow(stringResource(R.string.gs_gnss_rtcm_bytes), out.bytesTotal.fmtInt(" B"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), modifier = Modifier.fillMaxWidth()) {
            AdosButton(
                label = stringResource(R.string.gs_gnss_start_survey),
                onClick = { onBase(BaseModeRequest.Survey(SURVEY_MIN_S, SURVEY_ACCURACY_M)) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            AdosButton(
                label = stringResource(R.string.gs_gnss_set_fixed),
                onClick = { confirmFixed = true },
                enabled = !busy && receiver?.latDeg != null && receiver.lonDeg != null && receiver.hEllipsoidM != null,
                variant = AdosButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            AdosButton(
                label = stringResource(R.string.gs_gnss_base_stop),
                onClick = { onBase(BaseModeRequest.Off) },
                enabled = !busy && base?.mode != "off",
                variant = AdosButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val lat = receiver?.latDeg
    val lon = receiver?.lonDeg
    val h = receiver?.hEllipsoidM
    if (confirmFixed && lat != null && lon != null && h != null) {
        AlertDialog(
            onDismissRequest = { confirmFixed = false },
            title = { Text(stringResource(R.string.gs_gnss_set_fixed)) },
            text = { Text(stringResource(R.string.gs_gnss_set_fixed_message, "%.6f %.6f %.1f m".format(lat, lon, h))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmFixed = false
                    onBase(BaseModeRequest.Fixed(lat, lon, h))
                }) { Text(stringResource(R.string.gs_gnss_set_fixed)) }
            },
            dismissButton = { TextButton(onClick = { confirmFixed = false }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}

@Composable
private fun fixLabel(fix: String?): String = stringResource(
    when (fix) {
        "2d" -> R.string.gs_gnss_fix_2d
        "3d" -> R.string.gs_gnss_fix_3d
        "dgps" -> R.string.gs_gnss_fix_dgps
        "rtk_float" -> R.string.gs_gnss_fix_rtk_float
        "rtk_fixed" -> R.string.gs_gnss_fix_rtk_fixed
        else -> R.string.gs_gnss_fix_none
    },
)

private const val SURVEY_MIN_S = 300L
private const val SURVEY_ACCURACY_M = 2.0
