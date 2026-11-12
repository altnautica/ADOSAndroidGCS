package com.altnautica.gcs.feature.node.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import com.altnautica.gcs.feature.node.R

/** Words for the agent's video pipeline state; null for a value this app does not know. */
@StringRes
fun videoStateLabelRes(state: String): Int? = when (state) {
    "running" -> R.string.node_video_running
    "connecting" -> R.string.node_video_connecting
    "stopped" -> R.string.node_video_stopped
    "error" -> R.string.node_video_error
    "not_initialized" -> R.string.node_video_not_initialized
    else -> null
}

/** Words for a ground station's active uplink kind; null for an interface name or unknown kind. */
@StringRes
fun uplinkLabelRes(type: String): Int? = when (type) {
    "eth", "eth0", "ethernet" -> R.string.node_uplink_eth
    "wifi", "wlan0_client", "wifi_client" -> R.string.node_uplink_wifi
    "cellular", "wwan0", "modem" -> R.string.node_uplink_cellular
    "usb", "usb0" -> R.string.node_uplink_usb
    "none" -> R.string.node_uplink_none
    else -> null
}

/** Words for a radio link or modem connection state; null for a value this app does not know. */
@StringRes
fun linkStateLabelRes(state: String): Int? = when (state) {
    "connected" -> R.string.node_state_connected
    "connecting" -> R.string.node_state_connecting
    "disconnected" -> R.string.node_state_disconnected
    "searching" -> R.string.node_state_searching
    "stale" -> R.string.node_state_stale
    "unknown" -> R.string.node_state_unknown
    else -> null
}

/** Words for the receiver's link diagnosis; null for a verdict this app does not know. */
@StringRes
fun linkDiagnosisLabelRes(diag: String): Int? = when (diag) {
    "healthy" -> R.string.node_diag_healthy
    "searching" -> R.string.node_diag_searching
    "deaf" -> R.string.node_diag_deaf
    "mis_keyed" -> R.string.node_diag_mis_keyed
    "jammed" -> R.string.node_diag_jammed
    else -> null
}

/** Words for a systemd unit's active state; null for a value this app does not know. */
@StringRes
fun serviceStateLabelRes(state: String): Int? = when (state) {
    "active" -> R.string.node_service_active
    "inactive" -> R.string.node_service_inactive
    "failed" -> R.string.node_service_failed
    "activating" -> R.string.node_service_activating
    "deactivating" -> R.string.node_service_deactivating
    else -> null
}

/** Words for a ground station's mesh role; null for an unknown role. */
@StringRes
fun roleLabelRes(role: String): Int? = when (role) {
    "direct" -> R.string.gs_role_direct
    "relay" -> R.string.gs_role_relay
    "receiver" -> R.string.gs_role_receiver
    else -> null
}

@Composable
fun videoStateLabel(state: String?): String? = state?.let { s -> videoStateLabelRes(s)?.let { stringResource(it) } ?: s }

@Composable
fun uplinkLabel(type: String?): String? = type?.let { t -> uplinkLabelRes(t)?.let { stringResource(it) } ?: t }

@Composable
fun roleLabel(role: String?): String? = role?.let { r -> roleLabelRes(r)?.let { stringResource(it) } ?: r }

@Composable
fun linkStateLabel(state: String?): String? = state?.let { s -> linkStateLabelRes(s)?.let { stringResource(it) } ?: s }

@Composable
fun linkDiagnosisLabel(diag: String?): String? = diag?.let { d -> linkDiagnosisLabelRes(d)?.let { stringResource(it) } ?: d }

@Composable
fun serviceStateLabel(state: String?): String? = state?.let { s -> serviceStateLabelRes(s)?.let { stringResource(it) } ?: s }

private val SENSOR_ACRONYMS = setOf("cpu", "gpu", "soc", "npu", "ddr", "ve", "pmic")

/** A kernel thermal-zone key in words: `cpu_thermal` → "CPU", `soc-thermal` → "SoC", `big_core0` → "Big core0". */
fun sensorName(key: String): String {
    val words = key.lowercase().split('_', '-', ' ').filter { it.isNotEmpty() && it != "thermal" && it != "temp" }
    if (words.isEmpty()) return key
    return words.mapIndexed { i, w ->
        when {
            w == "soc" -> "SoC"
            w in SENSOR_ACRONYMS -> w.uppercase()
            i == 0 -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }
    }.joinToString(" ")
}

/**
 * The agent's last good status is too old to show as live: no success yet, or none for
 * [NodeVitalsRepository.MAX_POLL_AGE_MS] (the same rule the node header uses).
 */
fun pollStale(lastOkMs: Long?, nowMs: Long): Boolean =
    lastOkMs == null || nowMs - lastOkMs >= NodeVitalsRepository.MAX_POLL_AGE_MS
