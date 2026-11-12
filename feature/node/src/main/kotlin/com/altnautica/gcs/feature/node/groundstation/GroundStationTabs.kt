package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.component.overlay.AdosDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.linkDiagnosisLabel
import com.altnautica.gcs.feature.node.common.linkStateLabel
import com.altnautica.gcs.feature.node.common.roleLabelRes
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
                NodeTab.CarriedDrones -> item { LoadView(state.carried, vm::retry) { CarriedView(it, busy, onOpenNode, vm::releaseSlot) } }
                NodeTab.Radio -> {
                    item { LoadView(state.radio, vm::retry) { RadioView(it, busy, vm::setTxPower) } }
                    item { PhoneReceiversCard(nodeId) }
                }
                NodeTab.Network -> item {
                    LoadView(state.network, vm::retry) { net ->
                        NetworkView(net, state.scan, state.joinNeedsForce, busy, vm)
                    }
                }
                NodeTab.Gnss -> item { LoadView(lanes.gnss, vm::retry) { GnssView(it, busy, vm::setBase) } }
                NodeTab.Lora -> item { LoadView(lanes.lora, vm::retry) { LoraView(lanes, vm) } }
                NodeTab.Mesh -> item { LoadView(state.mesh, vm::retry) { MeshView(it, state.pairWindow, busy, vm) } }
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
        is Load.Ready -> Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            if (load.stale) StatusChip(StatusLevel.Warning, stringResource(R.string.node_stale))
            content(load.value)
        }
    }
}

// ---- radio -------------------------------------------------------------------

@Composable
private fun RadioView(r: RadioLink, busy: Boolean, onTxPower: (Int) -> Unit) {
    NodeCard(stringResource(R.string.gs_radio_link)) {
        KeyValueRow(stringResource(R.string.gs_radio_state), linkStateLabel(r.state))
        KeyValueRow(stringResource(R.string.gs_radio_diagnosis), linkDiagnosisLabel(r.diagnosis))
        KeyValueRow(stringResource(R.string.agent_wfb_rssi), r.rssiDbm.fmt(0, " dBm"))
        KeyValueRow(stringResource(R.string.agent_wfb_snr), r.snrDb.fmt(0, " dB"))
        KeyValueRow(stringResource(R.string.gs_radio_noise), r.noiseDbm.fmt(0, " dBm"))
        KeyValueRow(stringResource(R.string.agent_wfb_bitrate), r.bitrateMbps.fmt(1, " Mb/s"))
        KeyValueRow(stringResource(R.string.gs_radio_fec), r.fecRecovered?.let { rec -> "$rec / ${r.fecLost ?: "—"}" })
        KeyValueRow(stringResource(R.string.agent_wfb_loss), r.lossPercent.fmt(1, " %"))
        KeyValueRow(stringResource(R.string.gs_radio_mcs), r.mcsIndex.fmtInt())
    }
    r.adapters?.let { RadioAdaptersView(it) }
    NodeCard(stringResource(R.string.gs_radio_settings)) {
        // The channel follows the drone's pairing; the receiver retunes on its own, so it is read-only here.
        KeyValueRow(stringResource(R.string.agent_wfb_channel), r.channel?.toString() ?: stringResource(R.string.gs_radio_channel_unknown))
        Text(stringResource(R.string.gs_radio_channel_read_only), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
        val max = r.txPowerMaxDbm
        var power by rememberSaveable(r.txPowerDbm, max) { mutableStateOf(RadioLink.txPowerStart(r.txPowerDbm, max)) }
        var confirmPower by rememberSaveable { mutableStateOf(false) }
        NumberSettingRow(
            title = stringResource(R.string.agent_wfb_tx_power),
            value = power,
            onValueChange = { power = it.coerceIn(1, max) },
            range = 1..max,
            decrementDescription = stringResource(R.string.node_decrease),
            incrementDescription = stringResource(R.string.node_increase),
            format = { "$it dBm" },
            enabled = !busy,
        )
        Text(stringResource(R.string.gs_radio_tx_cap, max), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
        if (r.txPowerDbm?.toInt() != power) {
            AdosButton(stringResource(R.string.gs_apply), onClick = { confirmPower = true }, enabled = !busy)
        }
        if (confirmPower) {
            AdosConfirmSheet(
                title = stringResource(R.string.gs_tx_confirm_title, power),
                message = stringResource(R.string.gs_tx_confirm_message),
                onDismiss = { confirmPower = false },
            ) {
                AdosButton(
                    stringResource(R.string.gs_apply),
                    onClick = {
                        confirmPower = false
                        onTxPower(power)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
        AdosDialog(
            onDismiss = { pendingRole = null },
            title = stringResource(R.string.gs_mesh_role_change_title, roleLabel(role)),
            text = { Text(stringResource(R.string.gs_mesh_role_change_message)) },
            confirm = {
                TextButton(onClick = {
                    vm.setRole(role)
                    pendingRole = null
                }) { Text(stringResource(R.string.gs_apply)) }
            },
            dismiss = { TextButton(onClick = { pendingRole = null }) { Text(stringResource(R.string.node_cancel)) } },
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
private fun roleLabel(role: String): String = roleLabelRes(role)?.let { stringResource(it) } ?: role

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
