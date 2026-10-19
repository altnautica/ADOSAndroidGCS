package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.ProblemState
import com.altnautica.gcs.feature.node.common.WriteState
import com.altnautica.gcs.feature.node.common.WriteStatus
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.fmtInt

/** Body of one ground-station tab (Carried drones, Radio, Network, GNSS, LoRa, Mesh). */
@Composable
fun GroundStationTab(nodeId: String, tab: NodeTab, onOpenNode: (DeviceId) -> Unit, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<GroundStationViewModel, GroundStationViewModel.Factory>(key = "gs-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lanes by vm.lanes.state.collectAsStateWithLifecycle()
    val write by vm.writeState.collectAsStateWithLifecycle()
    DisposableEffect(tab) {
        vm.show(tab)
        onDispose { vm.show(null) }
    }
    val busy = write is WriteState.Running
    Column(modifier.fillMaxSize()) {
        WriteStatus(write, vm.writes, Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AdosSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            when (tab) {
                NodeTab.CarriedDrones -> item { LoadView(state.carried, { vm.show(tab) }) { CarriedView(it, busy, onOpenNode, vm::releaseSlot) } }
                NodeTab.Radio -> {
                    item { LoadView(state.radio, { vm.show(tab) }) { RadioView(it, busy, vm::setChannel, vm::setTxPower) } }
                    item { PhoneReceiversCard(nodeId) }
                }
                NodeTab.Network -> item {
                    LoadView(state.network, { vm.show(tab) }) { net ->
                        NetworkView(net, state.scan, state.joinNeedsForce, busy, vm)
                    }
                }
                NodeTab.Gnss -> item { LoadView(lanes.gnss, { vm.show(tab) }) { GnssView(it, busy, vm::setBase) } }
                NodeTab.Lora -> item { LoadView(lanes.lora, { vm.show(tab) }) { LoraView(lanes, vm) } }
                NodeTab.Mesh -> item { LoadView(state.mesh, { vm.show(tab) }) { MeshView(it, state.pairWindow, busy, vm) } }
                else -> Unit
            }
        }
    }
}

@Composable
private fun <T> LoadView(load: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> LoadingState(stringResource(R.string.node_loading))
        is Load.Failed -> ProblemState(load.problem, onRetry)
        is Load.Ready -> Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) { content(load.value) }
    }
}

// ---- radio -------------------------------------------------------------------

/** 5 GHz channels the ground station accepts for WFB. */
private val WFB_CHANNELS = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)

@Composable
private fun RadioView(r: RadioLink, busy: Boolean, onChannel: (Int) -> Unit, onTxPower: (Int) -> Unit) {
    NodeCard(stringResource(R.string.gs_radio_link)) {
        KeyValueRow(stringResource(R.string.gs_radio_state), r.state)
        KeyValueRow(stringResource(R.string.gs_radio_diagnosis), r.diagnosis)
        KeyValueRow(stringResource(R.string.agent_wfb_rssi), r.rssiDbm.fmt(0, " dBm"))
        KeyValueRow(stringResource(R.string.agent_wfb_snr), r.snrDb.fmt(0, " dB"))
        KeyValueRow(stringResource(R.string.gs_radio_noise), r.noiseDbm.fmt(0, " dBm"))
        KeyValueRow(stringResource(R.string.agent_wfb_bitrate), r.bitrateMbps.fmt(1, " Mb/s"))
        KeyValueRow(stringResource(R.string.gs_radio_fec), r.fecRecovered?.let { rec -> "$rec / ${r.fecLost ?: "—"}" })
        KeyValueRow(stringResource(R.string.agent_wfb_loss), r.lossPercent.fmt(1, " %"))
        KeyValueRow(stringResource(R.string.gs_radio_mcs), r.mcsIndex.fmtInt())
    }
    NodeCard(stringResource(R.string.gs_radio_settings)) {
        val channel = r.channel
        SelectSettingRow(
            title = stringResource(R.string.agent_wfb_channel),
            options = if (channel == null || channel in WFB_CHANNELS) WFB_CHANNELS else listOf(channel) + WFB_CHANNELS,
            selected = channel ?: WFB_CHANNELS.first(),
            optionLabel = { it.toString() },
            onSelect = { if (it != channel) onChannel(it) },
            summary = if (channel == null) stringResource(R.string.gs_radio_channel_unknown) else null,
            enabled = !busy,
        )
        var power by rememberSaveable(r.txPowerDbm) { mutableStateOf(r.txPowerDbm?.toInt() ?: 20) }
        NumberSettingRow(
            title = stringResource(R.string.agent_wfb_tx_power),
            value = power,
            onValueChange = { power = it },
            range = 1..30,
            decrementDescription = stringResource(R.string.node_decrease),
            incrementDescription = stringResource(R.string.node_increase),
            format = { "$it dBm" },
            enabled = !busy,
        )
        if (r.txPowerDbm?.toInt() != power) {
            AdosButton(stringResource(R.string.gs_apply), onClick = { onTxPower(power) }, enabled = !busy)
        }
    }
}

// ---- network -----------------------------------------------------------------

@Composable
private fun NetworkView(
    n: StationNetwork,
    scan: Load<List<ScannedNetwork>>?,
    joinNeedsForce: PendingJoin?,
    busy: Boolean,
    vm: GroundStationViewModel,
) {
    NodeCard(stringResource(R.string.gs_net_uplink)) {
        KeyValueRow(stringResource(R.string.gs_net_active_uplink), n.activeUplink)
        KeyValueRow(stringResource(R.string.gs_net_priority), n.priority.joinToString(" › ").ifEmpty { null })
        KeyValueRow(stringResource(R.string.gs_net_ethernet), n.ethernetLink?.let { if (it) n.ethernetIp ?: "" else null })
        n.shareUplink?.let { share ->
            SwitchSettingRow(
                title = stringResource(R.string.gs_net_share_uplink),
                checked = share,
                onCheckedChange = vm::setShareUplink,
                summary = stringResource(R.string.gs_net_share_uplink_summary),
                enabled = !busy,
            )
        }
    }
    n.ap?.let { ap -> AccessPointCard(ap, busy, vm) }
    WifiClientCard(n.client, scan, busy, vm)
    n.modem?.let { m -> ModemCard(m, busy, vm) }
    joinNeedsForce?.let { join ->
        AlertDialog(
            onDismissRequest = vm::cancelJoin,
            title = { Text(stringResource(R.string.gs_net_join_force_title)) },
            text = { Text(stringResource(R.string.gs_net_join_force_message, join.ssid)) },
            confirmButton = { TextButton(onClick = { vm.join(join.ssid, join.passphrase, standDownAccessPoint = true) }) { Text(stringResource(R.string.gs_net_join)) } },
            dismissButton = { TextButton(onClick = vm::cancelJoin) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}

@Composable
private fun AccessPointCard(ap: AccessPoint, busy: Boolean, vm: GroundStationViewModel) {
    var ssid by rememberSaveable(ap.ssid) { mutableStateOf(ap.ssid.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf("") }
    var channel by rememberSaveable(ap.channel) { mutableStateOf(ap.channel ?: 6) }
    NodeCard(stringResource(R.string.gs_net_ap)) {
        SwitchSettingRow(
            title = stringResource(R.string.gs_net_ap_enabled),
            checked = ap.enabled == true,
            onCheckedChange = { vm.saveAccessPoint(it, null, null, null) },
            summary = when {
                ap.standingDown -> stringResource(R.string.gs_net_ap_standing_down)
                ap.running == true -> stringResource(R.string.gs_net_ap_clients, ap.clients)
                else -> null
            },
            enabled = !busy,
        )
        OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.gs_net_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass,
            { pass = it },
            label = { Text(stringResource(R.string.gs_net_passphrase)) },
            placeholder = { Text(stringResource(R.string.gs_net_passphrase_keep)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        SelectSettingRow(
            title = stringResource(R.string.agent_wfb_channel),
            options = (1..11).toList(),
            selected = channel.coerceIn(1, 11),
            optionLabel = { it.toString() },
            onSelect = { channel = it },
            enabled = !busy,
        )
        val dirty = ssid != ap.ssid.orEmpty() || pass.isNotEmpty() || channel != ap.channel
        AdosButton(
            label = stringResource(R.string.gs_apply),
            onClick = {
                vm.saveAccessPoint(null, ssid.takeIf { it != ap.ssid }, pass.ifEmpty { null }, channel.takeIf { it != ap.channel })
                pass = ""
            },
            enabled = dirty && !busy && (pass.isEmpty() || pass.length >= MIN_PASSPHRASE),
        )
    }
}

private const val MIN_PASSPHRASE = 8

@Composable
private fun WifiClientCard(client: WifiClient?, scan: Load<List<ScannedNetwork>>?, busy: Boolean, vm: GroundStationViewModel) {
    var joining by rememberSaveable { mutableStateOf<String?>(null) }
    NodeCard(stringResource(R.string.gs_net_client)) {
        KeyValueRow(
            stringResource(R.string.gs_net_client_status),
            client?.connected?.let { if (it) client.ssid ?: "" else null },
        )
        KeyValueRow(stringResource(R.string.gs_net_client_signal), client?.signal.fmtInt(" dBm"))
        KeyValueRow(stringResource(R.string.gs_net_client_ip), client?.ip)
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            AdosButton(stringResource(R.string.gs_net_scan), onClick = vm::scanNetworks, variant = AdosButtonVariant.Secondary, icon = Icons.Outlined.Wifi, enabled = !busy)
            if (client?.connected == true) {
                AdosButton(stringResource(R.string.gs_net_leave), onClick = vm::leaveNetwork, variant = AdosButtonVariant.Secondary, enabled = !busy)
            }
        }
        when (scan) {
            null -> Unit
            Load.Loading -> LoadingState(stringResource(R.string.gs_net_scanning))
            is Load.Failed -> ProblemState(scan.problem, vm::scanNetworks)
            is Load.Ready -> if (scan.value.isEmpty()) {
                Text(stringResource(R.string.gs_net_scan_empty), color = AdosTheme.colors.fgSecondary)
            } else {
                scan.value.forEach { net ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { joining = net.ssid },
                    ) {
                        Text(net.ssid, modifier = Modifier.weight(1f))
                        Text(listOfNotNull(net.signalDbm?.let { "$it dBm" }, net.security).joinToString(" · "), color = AdosTheme.colors.fgSecondary)
                    }
                }
            }
        }
    }
    joining?.let { ssid ->
        val open = (scan as? Load.Ready)?.value?.firstOrNull { it.ssid == ssid }?.open == true
        var pass by rememberSaveable(ssid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { joining = null },
            title = { Text(stringResource(R.string.gs_net_join_title, ssid)) },
            text = {
                if (!open) {
                    OutlinedTextField(
                        pass,
                        { pass = it },
                        label = { Text(stringResource(R.string.gs_net_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.join(ssid, pass.ifEmpty { null })
                        joining = null
                    },
                    enabled = open || pass.length >= MIN_PASSPHRASE,
                ) { Text(stringResource(R.string.gs_net_join)) }
            },
            dismissButton = { TextButton(onClick = { joining = null }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}

@Composable
private fun ModemCard(m: Modem, busy: Boolean, vm: GroundStationViewModel) {
    var apn by rememberSaveable(m.apn) { mutableStateOf(m.apn.orEmpty()) }
    var cap by rememberSaveable(m.capMb) { mutableStateOf(m.capMb?.toString().orEmpty()) }
    NodeCard(stringResource(R.string.gs_net_modem)) {
        SwitchSettingRow(
            title = stringResource(R.string.gs_net_modem_enabled),
            checked = m.enabled == true,
            onCheckedChange = { vm.saveModem(it, null, null) },
            summary = m.state,
            enabled = !busy,
        )
        KeyValueRow(stringResource(R.string.gs_net_modem_used), m.dataUsedMb.fmtInt(" MB"))
        OutlinedTextField(apn, { apn = it }, label = { Text(stringResource(R.string.gs_net_apn)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            cap,
            { cap = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.gs_net_cap)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        val dirty = apn != m.apn.orEmpty() || cap != m.capMb?.toString().orEmpty()
        AdosButton(
            stringResource(R.string.gs_apply),
            onClick = { vm.saveModem(null, apn.takeIf { it != m.apn.orEmpty() }, cap.toIntOrNull()?.takeIf { it != m.capMb }) },
            enabled = dirty && !busy,
        )
    }
}

// ---- mesh --------------------------------------------------------------------

@Composable
private fun MeshView(m: MeshState, window: PairWindow?, busy: Boolean, vm: GroundStationViewModel) {
    var pendingRole by rememberSaveable { mutableStateOf<String?>(null) }
    NodeCard(stringResource(R.string.gs_mesh_role)) {
        val roles = m.supportedRoles.ifEmpty { listOfNotNull(m.role) }
        val labels = roles.associateWith { roleLabel(it) }
        if (roles.isNotEmpty()) {
            SelectSettingRow(
                title = stringResource(R.string.gs_mesh_role),
                options = roles,
                selected = m.role ?: roles.first(),
                optionLabel = { labels.getValue(it) },
                onSelect = { if (it != m.role) pendingRole = it },
                summary = stringResource(R.string.gs_mesh_role_summary),
                enabled = !busy,
            )
        }
        KeyValueRow(stringResource(R.string.gs_mesh_configured), m.configuredRole?.let { roleLabel(it) })
    }
    pendingRole?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingRole = null },
            title = { Text(stringResource(R.string.gs_mesh_role_change_title, roleLabel(role))) },
            text = { Text(stringResource(R.string.gs_mesh_role_change_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setRole(role)
                    pendingRole = null
                }) { Text(stringResource(R.string.gs_apply)) }
            },
            dismissButton = { TextButton(onClick = { pendingRole = null }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
    if (m.role == "direct" || m.up == null) {
        Text(stringResource(R.string.gs_mesh_not_in_mesh), color = AdosTheme.colors.fgSecondary)
    } else {
        NodeCard(stringResource(R.string.gs_mesh_health)) {
            StatusChip(
                level = if (m.up && m.partition != true) StatusLevel.Success else StatusLevel.Warning,
                label = stringResource(
                    when {
                        !m.up -> R.string.gs_mesh_down
                        m.partition == true -> R.string.gs_mesh_partition
                        else -> R.string.gs_mesh_up
                    },
                ),
            )
            KeyValueRow(stringResource(R.string.gs_mesh_id), m.meshId)
            KeyValueRow(stringResource(R.string.gs_mesh_neighbors), m.neighbors.size.toString())
            m.neighbors.forEach { KeyValueRow(it.mac, it.tq?.let { tq -> "TQ $tq" }) }
            m.gateways.forEach { g ->
                KeyValueRow(
                    if (g.selected) stringResource(R.string.gs_mesh_gateway_selected, g.mac) else g.mac,
                    g.tq?.let { tq -> "TQ $tq" },
                )
            }
        }
    }
    if (m.role == "receiver") PairWindowCard(window, busy, vm)
    if (m.role == "relay") JoinMeshCard(busy, vm)
}

@Composable
private fun roleLabel(role: String): String = when (role) {
    "direct" -> stringResource(R.string.gs_role_direct)
    "relay" -> stringResource(R.string.gs_role_relay)
    "receiver" -> stringResource(R.string.gs_role_receiver)
    else -> role
}

@Composable
private fun PairWindowCard(window: PairWindow?, busy: Boolean, vm: GroundStationViewModel) {
    NodeCard(stringResource(R.string.gs_pair_window)) {
        if (window == null) {
            KeyValueRow(stringResource(R.string.gs_pair_window_state), null)
            return@NodeCard
        }
        if (window.open) {
            KeyValueRow(stringResource(R.string.gs_pair_code), window.code)
            AdosButton(stringResource(R.string.gs_pair_close), onClick = vm::closePairWindow, variant = AdosButtonVariant.Secondary, enabled = !busy)
        } else {
            AdosButton(stringResource(R.string.gs_pair_open), onClick = { vm.openPairWindow() }, enabled = !busy)
        }
        window.pending.forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(id, modifier = Modifier.weight(1f))
                if (id in window.approved) {
                    StatusChip(StatusLevel.Success, stringResource(R.string.gs_pair_approved))
                } else {
                    TextButton(onClick = { vm.approve(id) }, enabled = !busy) { Text(stringResource(R.string.gs_pair_approve)) }
                }
            }
        }
    }
}

@Composable
private fun JoinMeshCard(busy: Boolean, vm: GroundStationViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    NodeCard(stringResource(R.string.gs_join_mesh)) {
        OutlinedTextField(
            code,
            { code = it.filter(Char::isDigit).take(JOIN_CODE_LENGTH) },
            label = { Text(stringResource(R.string.gs_join_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        AdosButton(stringResource(R.string.gs_join_mesh), onClick = { vm.joinMesh(code) }, enabled = code.length == JOIN_CODE_LENGTH && !busy)
    }
}

private const val JOIN_CODE_LENGTH = 6
