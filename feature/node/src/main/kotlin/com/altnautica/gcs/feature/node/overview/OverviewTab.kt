package com.altnautica.gcs.feature.node.overview

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.node.NodePoll
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.ProblemState
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.fmtInt

@Composable
fun OverviewTab(nodeId: String, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<OverviewViewModel, OverviewViewModel.Factory>(key = "overview-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    OverviewContent(state, modifier)
}

@Composable
fun OverviewContent(state: OverviewUiState, modifier: Modifier = Modifier) {
    val node = state.node ?: return LoadingState(stringResource(R.string.node_loading), modifier)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        item { IdentityCard(node, state.agent) }
        state.agent?.let { poll -> item { AgentCard(poll) } }
        state.agent?.status?.let { status -> item { FlightCard(status) } }
        state.station?.let { load -> item { StationCards(load) } }
        state.vehicle?.let { v -> item { VehicleCard(v) } }
    }
}

@Composable
private fun IdentityCard(node: NodeSummary, poll: NodePoll?) {
    NodeCard(stringResource(R.string.overview_identity)) {
        KeyValueRow(stringResource(R.string.overview_device_id), node.id.value)
        KeyValueRow(stringResource(R.string.overview_agent_version), poll?.agentVersion ?: node.agentVersion)
        KeyValueRow(stringResource(R.string.overview_fc), fcLabel(node.fcVariant, poll?.status?.fcVariant))
        node.carriedDroneId?.let { KeyValueRow(stringResource(R.string.overview_carried_drone), it.value) }
    }
}

@Composable
private fun fcLabel(variant: FcVariant, agentVariant: String?): String? = when (variant) {
    FcVariant.MavlinkArdupilot -> stringResource(R.string.overview_fc_ardupilot)
    FcVariant.MavlinkPx4 -> stringResource(R.string.overview_fc_px4)
    FcVariant.Msp -> stringResource(R.string.overview_fc_msp)
    FcVariant.Unknown -> agentVariant
}

@Composable
private fun AgentCard(poll: NodePoll) {
    val status = poll.status
    NodeCard(stringResource(R.string.overview_agent)) {
        if (status == null) {
            KeyValueRow(stringResource(R.string.overview_last_status), null)
            return@NodeCard
        }
        val ageS = poll.lastOkMs?.let { (SystemClock.elapsedRealtime() - it) / 1000 }
        KeyValueRow(stringResource(R.string.overview_last_status), ageS?.let { stringResource(R.string.overview_seconds_ago, it) })
        KeyValueRow(stringResource(R.string.overview_uptime), status.uptimeSeconds?.let { uptime(it.toLong()) })
        KeyValueRow(
            stringResource(R.string.overview_fc_link),
            status.fcConnected?.let { stringResource(if (it) R.string.overview_connected else R.string.overview_disconnected) },
        )
        KeyValueRow(stringResource(R.string.overview_fc_port), status.fcPort?.let { p -> status.fcBaud?.let { "$p @ $it" } ?: p })
        KeyValueRow(stringResource(R.string.overview_fc_firmware), status.fcFirmware)
        KeyValueRow(stringResource(R.string.overview_heartbeat_age), status.heartbeatAgeS.fmt(1, " s"))
        KeyValueRow(stringResource(R.string.overview_video), status.video?.state)
        KeyValueRow(stringResource(R.string.overview_streams), status.video?.streams?.size?.toString())
        KeyValueRow(
            stringResource(R.string.overview_recording),
            status.video?.recording?.let { stringResource(if (it) R.string.overview_yes else R.string.overview_no) },
        )
    }
}

@Composable
private fun FlightCard(status: StatusFull) {
    val t = status.telemetry ?: return
    NodeCard(stringResource(R.string.overview_flight)) {
        KeyValueRow(stringResource(R.string.overview_armed), t.armed?.let { stringResource(if (it) R.string.overview_yes else R.string.overview_no) })
        KeyValueRow(stringResource(R.string.overview_mode), t.mode?.takeIf { it.isNotBlank() })
        KeyValueRow(stringResource(R.string.overview_battery), t.battery?.remaining?.takeIf { it >= 0 }.fmtInt(" %"))
        KeyValueRow(stringResource(R.string.overview_voltage), t.battery?.voltage.fmt(2, " V"))
        KeyValueRow(stringResource(R.string.overview_gps), t.gps?.let { g -> g.fixType?.let { fix -> "${gpsFixLabel(fix)} · ${g.satellites ?: "—"}" } })
        KeyValueRow(stringResource(R.string.overview_altitude), t.position?.altRel.fmt(1, " m"))
        KeyValueRow(stringResource(R.string.overview_groundspeed), t.velocity?.groundspeed.fmt(1, " m/s"))
    }
}

@Composable
private fun StationCards(load: StationLoad) {
    when (load) {
        StationLoad.Loading -> LoadingState(stringResource(R.string.node_loading))
        is StationLoad.Failed -> ProblemState(load.problem, onRetry = {})
        is StationLoad.Ready -> {
            val s = load.status
            NodeCard(stringResource(R.string.overview_station)) {
                KeyValueRow(stringResource(R.string.overview_role), s.role.current)
                KeyValueRow(stringResource(R.string.overview_paired_drone), s.pairedDrone.deviceId)
                KeyValueRow(stringResource(R.string.overview_rssi), s.link.rssiDbm.fmtInt(" dBm"))
                KeyValueRow(stringResource(R.string.overview_bitrate), s.link.bitrateMbps?.toDouble().fmt(1, " Mb/s"))
                KeyValueRow(stringResource(R.string.overview_channel), s.link.channel?.toString())
                KeyValueRow(stringResource(R.string.overview_clients), s.gcs.clients.size.toString())
                KeyValueRow(stringResource(R.string.overview_pic), s.gcs.picId)
                KeyValueRow(stringResource(R.string.overview_uplink), s.network.uplinkType)
                KeyValueRow(stringResource(R.string.overview_ap), s.network.apSsid)
                KeyValueRow(
                    stringResource(R.string.overview_recording),
                    stringResource(if (s.recording) R.string.overview_yes else R.string.overview_no),
                )
            }
            NodeCard(stringResource(R.string.overview_system)) {
                KeyValueRow(stringResource(R.string.overview_cpu), s.system.cpuPct?.toDouble().fmt(0, " %"))
                KeyValueRow(
                    stringResource(R.string.overview_memory),
                    s.system.ramUsedMb?.let { used -> s.system.ramTotalMb?.let { "$used / $it MB" } ?: "$used MB" },
                )
                KeyValueRow(stringResource(R.string.overview_temperature), s.system.tempC?.toDouble().fmt(0, " °C"))
                KeyValueRow(stringResource(R.string.overview_uptime), s.system.uptimeSeconds?.let(::uptime))
            }
        }
    }
}

@Composable
private fun VehicleCard(v: VehicleSnapshot) {
    NodeCard(stringResource(R.string.overview_flight)) {
        KeyValueRow(stringResource(R.string.overview_armed), v.heartbeat?.armed?.let { stringResource(if (it) R.string.overview_yes else R.string.overview_no) })
        KeyValueRow(stringResource(R.string.overview_mode), v.heartbeat?.mode?.name)
        KeyValueRow(stringResource(R.string.overview_battery), v.battery?.remainingPct?.takeIf { it >= 0 }.fmtInt(" %"))
        KeyValueRow(stringResource(R.string.overview_voltage), v.battery?.voltageV?.toDouble().fmt(2, " V"))
        KeyValueRow(stringResource(R.string.overview_gps), v.gps?.let { "${fixName(it.fix)} · ${it.satellites ?: "—"}" })
        KeyValueRow(stringResource(R.string.overview_heartbeat_age), v.link?.heartbeatAgeMs?.let { (it / 100 / 10.0).fmt(1, " s") })
        KeyValueRow(stringResource(R.string.overview_rssi), v.link?.rssiDbm.fmtInt(" dBm"))
    }
}

/** MAVLink `GPS_FIX_TYPE` as a short label. */
@Composable
private fun gpsFixLabel(fix: Int): String = when (fix) {
    0 -> fixName(GpsFix.NoGps)
    1 -> fixName(GpsFix.NoFix)
    2 -> fixName(GpsFix.Fix2D)
    3 -> fixName(GpsFix.Fix3D)
    4 -> fixName(GpsFix.DGps)
    5 -> fixName(GpsFix.RtkFloat)
    6 -> fixName(GpsFix.RtkFixed)
    else -> fix.toString()
}

@Composable
private fun fixName(fix: GpsFix): String = stringResource(
    when (fix) {
        GpsFix.NoGps -> R.string.gps_no_gps
        GpsFix.NoFix -> R.string.gps_no_fix
        GpsFix.Fix2D -> R.string.gps_2d
        GpsFix.Fix3D -> R.string.gps_3d
        GpsFix.DGps -> R.string.gps_dgps
        GpsFix.RtkFloat -> R.string.gps_rtk_float
        GpsFix.RtkFixed -> R.string.gps_rtk_fixed
    },
)

private fun uptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3_600
    val m = (seconds % 3_600) / 60
    return if (d > 0) "${d}d ${h}h ${m}m" else "${h}h ${m}m"
}
