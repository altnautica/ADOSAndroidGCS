package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.component.overlay.AdosDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.linkStateLabel
import com.altnautica.gcs.feature.node.common.uplinkLabel
import com.altnautica.gcs.feature.node.common.ProblemState
import com.altnautica.gcs.feature.node.common.fmtInt
// ---- network -----------------------------------------------------------------

@Composable
internal fun NetworkView(
    n: StationNetwork,
    scan: Load<List<ScannedNetwork>>?,
    joinNeedsForce: PendingJoin?,
    busy: Boolean,
    vm: GroundStationViewModel,
) {
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    NodeCard(stringResource(R.string.gs_net_uplink)) {
        KeyValueRow(stringResource(R.string.gs_net_active_uplink), uplinkLabel(n.activeUplink))
        val priority = n.priority.map { uplinkLabel(it).orEmpty() }
        KeyValueRow(stringResource(R.string.gs_net_priority), priority.joinToString(" › ").ifEmpty { null })
        KeyValueRow(stringResource(R.string.gs_net_ethernet), n.ethernetLink?.let { if (it) n.ethernetIp ?: "" else null })
        n.shareUplink?.let { share ->
            SwitchSettingRow(
                title = stringResource(R.string.gs_net_share_uplink),
                checked = share,
                onCheckedChange = { on -> if (shareNeedsConfirm(on)) confirmShare = true else vm.setShareUplink(on) },
                summary = stringResource(R.string.gs_net_share_uplink_summary),
                enabled = !busy,
            )
        }
    }
    if (confirmShare) {
        AdosConfirmSheet(
            title = stringResource(R.string.gs_share_confirm_title),
            message = stringResource(R.string.gs_share_confirm_message),
            onDismiss = { confirmShare = false },
        ) {
            AdosButton(
                stringResource(R.string.gs_share_confirm),
                onClick = {
                    confirmShare = false
                    vm.setShareUplink(true)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    n.ap?.let { ap -> AccessPointCard(ap, busy, vm) }
    WifiClientCard(n.client, scan, busy, vm)
    n.modem?.let { m -> ModemCard(m, busy, vm) }
    joinNeedsForce?.let { join ->
        AdosDialog(
            onDismiss = vm::cancelJoin,
            title = stringResource(R.string.gs_net_join_force_title),
            text = { Text(stringResource(R.string.gs_net_join_force_message, join.ssid)) },
            confirm = { TextButton(onClick = { vm.join(join.ssid, join.passphrase, standDownAccessPoint = true) }) { Text(stringResource(R.string.gs_net_join)) } },
            dismiss = { TextButton(onClick = vm::cancelJoin) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}

@Composable
private fun AccessPointCard(ap: AccessPoint, busy: Boolean, vm: GroundStationViewModel) {
    var ssid by rememberSaveable(ap.ssid) { mutableStateOf(ap.ssid.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf("") }
    var channel by rememberSaveable(ap.channel) { mutableStateOf(ap.channel) }
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    NodeCard(stringResource(R.string.gs_net_ap)) {
        SwitchSettingRow(
            title = stringResource(R.string.gs_net_ap_enabled),
            checked = ap.enabled == true,
            onCheckedChange = { on -> if (apSwitchNeedsConfirm(on)) confirmOff = true else vm.saveAccessPoint(on, null, null, null) },
            summary = when {
                ap.standingDown -> stringResource(R.string.gs_net_ap_standing_down)
                ap.running == true -> stringResource(R.string.gs_net_ap_clients, ap.clients)
                else -> null
            },
            enabled = !busy,
        )
        if (confirmOff) {
            AdosConfirmSheet(
                title = stringResource(R.string.gs_ap_off_confirm_title),
                message = stringResource(R.string.gs_ap_off_confirm_message),
                onDismiss = { confirmOff = false },
                danger = true,
            ) {
                AdosButton(
                    stringResource(R.string.gs_ap_off_confirm),
                    onClick = {
                        confirmOff = false
                        vm.saveAccessPoint(false, null, null, null)
                    },
                    variant = AdosButtonVariant.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
        val options = apChannelOptions(ap.channel)
        SelectSettingRow(
            title = stringResource(R.string.agent_wfb_channel),
            options = options,
            selected = channel ?: options.first(),
            optionLabel = { it.toString() },
            onSelect = { channel = it },
            summary = if (channel == null) stringResource(R.string.gs_radio_channel_unknown) else null,
            enabled = !busy,
        )
        val dirty = ssid != ap.ssid.orEmpty() || pass.isNotEmpty() || (channel != null && channel != ap.channel)
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

/** 2.4 GHz access-point channels; a current channel outside the list is offered as-is, never coerced. */
private val AP_CHANNELS = (1..13).toList()

internal fun apChannelOptions(current: Int?): List<Int> =
    if (current == null || current in AP_CHANNELS) AP_CHANNELS else listOf(current) + AP_CHANNELS

/** Turning the access point off drops every phone on it, maybe this one: ask first. */
internal fun apSwitchNeedsConfirm(turnOn: Boolean): Boolean = !turnOn

/** Sharing the uplink installs NAT and can spend metered data: ask first. Stopping it does not. */
internal fun shareNeedsConfirm(turnOn: Boolean): Boolean = turnOn

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
                var confirmLeave by rememberSaveable { mutableStateOf(false) }
                AdosButton(stringResource(R.string.gs_net_leave), onClick = { confirmLeave = true }, variant = AdosButtonVariant.Secondary, enabled = !busy)
                if (confirmLeave) {
                    val ssid = client.ssid.orEmpty()
                    AdosConfirmSheet(
                        title = stringResource(R.string.gs_leave_confirm_title, ssid),
                        message = stringResource(R.string.gs_leave_confirm_message),
                        onDismiss = { confirmLeave = false },
                        danger = true,
                    ) {
                        AdosButton(
                            stringResource(R.string.gs_net_leave),
                            onClick = {
                                confirmLeave = false
                                vm.leaveNetwork(ssid)
                            },
                            variant = AdosButtonVariant.Danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = AdosTheme.touchMin).clickable(enabled = !busy) { joining = net.ssid },
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
        AdosDialog(
            onDismiss = { joining = null },
            title = stringResource(R.string.gs_net_join_title, ssid),
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
            confirm = {
                TextButton(
                    onClick = {
                        vm.join(ssid, pass.ifEmpty { null })
                        joining = null
                    },
                    enabled = open || pass.length >= MIN_PASSPHRASE,
                ) { Text(stringResource(R.string.gs_net_join)) }
            },
            dismiss = { TextButton(onClick = { joining = null }) { Text(stringResource(R.string.node_cancel)) } },
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
            summary = linkStateLabel(m.state),
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
