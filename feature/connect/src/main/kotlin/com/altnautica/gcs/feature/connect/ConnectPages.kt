package com.altnautica.gcs.feature.connect

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.transport.UsbSerialTransport
import com.altnautica.gcs.core.agent.wifi.WifiJoinState
import com.altnautica.gcs.core.agent.node.LanNodeAssembler
import com.altnautica.gcs.core.designsystem.component.node.profileLabel
import com.altnautica.gcs.core.model.NodeProfile
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/** Example address shown in empty host fields. */
private const val HOST_PLACEHOLDER = "192.168.1.50"

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) { content() }
}

@Composable
private fun inputErrorText(e: InputError): String = stringResource(
    when (e) {
        InputError.Required -> R.string.connect_input_required
        InputError.InvalidPort -> R.string.connect_input_port
        InputError.InvalidHost -> R.string.connect_input_host
        InputError.InvalidCode -> R.string.connect_input_code
        InputError.KeyTooShort -> R.string.connect_input_key
    },
)

@Composable
internal fun CodePage(state: ConnectUiState, vm: ConnectViewModel) = FormColumn {
    Text(stringResource(R.string.connect_code_help), color = AdosTheme.colors.fgSecondary)
    OutlinedTextField(
        value = state.code,
        onValueChange = vm::setCode,
        label = { Text(stringResource(R.string.connect_code_field)) },
        singleLine = true,
        isError = state.codeError != null,
        supportingText = state.codeError?.let { { Text(inputErrorText(it)) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.fromLink) {
        StatusChip(
            StatusLevel.Info,
            if (state.host.isBlank()) stringResource(R.string.connect_link_no_host) else stringResource(R.string.connect_link_host, state.host),
        )
    }
    BusyRow(state)
    AdosButton(stringResource(R.string.connect_pair), onClick = vm::submitCode, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
}

/** What a busy page is doing, so a long wait does not look like nothing happened. */
@Composable
private fun BusyRow(state: ConnectUiState) {
    if (!state.busy) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(busyTextRes(state)), color = AdosTheme.colors.fgSecondary)
    }
}

@Composable
internal fun HostPage(state: ConnectUiState, vm: ConnectViewModel) = FormColumn {
    Text(stringResource(R.string.connect_host_help), color = AdosTheme.colors.fgSecondary)
    OutlinedTextField(
        value = state.host,
        onValueChange = vm::setHost,
        label = { Text(stringResource(R.string.connect_host_field)) },
        placeholder = { Text(HOST_PLACEHOLDER) },
        singleLine = true,
        isError = state.hostError != null,
        supportingText = state.hostError?.let { { Text(inputErrorText(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    state.needsKeyFor?.let {
        StatusChip(StatusLevel.Info, stringResource(R.string.connect_needs_key, it))
    }
    OutlinedTextField(
        value = state.apiKey,
        onValueChange = vm::setApiKey,
        label = { Text(stringResource(R.string.connect_key_field)) },
        supportingText = { Text(state.apiKeyError?.let { inputErrorText(it) } ?: stringResource(R.string.connect_key_help)) },
        isError = state.apiKeyError != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    BusyRow(state)
    AdosButton(
        label = stringResource(if (state.apiKey.isBlank()) R.string.connect_pair else R.string.connect_add_with_key),
        onClick = vm::submitHost,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun UsbPage(state: ConnectUiState, vm: ConnectViewModel) {
    // Re-scan on open and every resume: a cable plugged in while away shows up on return.
    LifecycleResumeEffect(Unit) {
        vm.refreshUsb()
        onPauseOrDispose { }
    }
    FormColumn {
        SelectSettingRow(
            title = stringResource(R.string.connect_baud),
            options = UsbSerialTransport.BAUD_RATES.toList(),
            selected = state.baudRate,
            optionLabel = { it.toString() },
            onSelect = vm::setBaudRate,
        )
        if (state.usbDevices.isEmpty()) {
            Text(stringResource(R.string.connect_usb_none), color = AdosTheme.colors.fgSecondary)
        }
        state.usbDevices.forEach { d ->
            OptionRow(
                Icons.Outlined.Usb,
                d.label,
                stringResource(if (d.hasPermission) R.string.connect_usb_ready else R.string.connect_usb_permission),
            ) { vm.openUsb(d) }
        }
        AdosButton(
            label = stringResource(R.string.connect_usb_refresh),
            onClick = { vm.refreshUsb() },
            variant = AdosButtonVariant.Secondary,
            icon = Icons.Outlined.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkPage(state: ConnectUiState, vm: ConnectViewModel) = FormColumn {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = state.udp, onClick = { vm.setUdp(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
            Text(stringResource(R.string.connect_udp))
        }
        SegmentedButton(selected = !state.udp, onClick = { vm.setUdp(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
            Text(stringResource(R.string.connect_tcp))
        }
    }
    if (state.udp) {
        Text(stringResource(R.string.connect_udp_help), color = AdosTheme.colors.fgSecondary)
        PortField(state.udpPort, vm::setUdpPort, R.string.connect_listen_port, state.udpPortError)
    } else {
        Text(stringResource(R.string.connect_tcp_help), color = AdosTheme.colors.fgSecondary)
        OutlinedTextField(
            value = state.tcpHost,
            onValueChange = vm::setTcpHost,
            label = { Text(stringResource(R.string.connect_host_field)) },
            placeholder = { Text(HOST_PLACEHOLDER) },
            singleLine = true,
            isError = state.tcpHostError != null,
            supportingText = state.tcpHostError?.let { { Text(inputErrorText(it)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        PortField(state.tcpPort, vm::setTcpPort, R.string.connect_port, state.tcpPortError)
    }
    BusyRow(state)
    AdosButton(stringResource(R.string.connect_add), onClick = vm::submitNetwork, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PortField(value: String, onChange: (String) -> Unit, label: Int, error: InputError?) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(inputErrorText(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Runtime permission the ground-station Wi-Fi join needs. */
private fun wifiJoinPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
internal fun WifiJoinPage(state: ConnectUiState, vm: ConnectViewModel) {
    val context = LocalContext.current
    val permission = wifiJoinPermission()
    fun isGranted() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var granted by rememberSaveable { mutableStateOf(isGranted()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    // The user may grant or revoke it in system settings while away; re-read on every resume.
    LifecycleResumeEffect(permission) {
        granted = isGranted()
        if (granted) {
            denied = false
            permanentlyDenied = false
        }
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        // Denied without a rationale to show: the system will not prompt again.
        permanentlyDenied = !ok && context.findActivity()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true
        if (ok) vm.joinGroundStationWifi()
    }
    FormColumn {
        Text(stringResource(R.string.connect_wifi_help), color = AdosTheme.colors.fgSecondary)
        OutlinedTextField(
            value = state.joinText,
            onValueChange = vm::setJoinText,
            label = { Text(stringResource(R.string.connect_wifi_field)) },
            singleLine = true,
            isError = state.joinError != null,
            supportingText = state.joinError?.let { { Text(inputErrorText(it)) } },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!granted) {
            StatusChip(
                StatusLevel.Info,
                stringResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.connect_wifi_rationale_nearby else R.string.connect_wifi_rationale_location),
            )
        }
        if (denied && !granted) StatusChip(StatusLevel.Warning, stringResource(R.string.connect_wifi_denied))
        when (state.wifiJoin) {
            WifiJoinState.Requesting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.connect_wifi_joining), color = AdosTheme.colors.fgSecondary)
            }
            WifiJoinState.Joined -> StatusChip(StatusLevel.Success, stringResource(R.string.connect_wifi_joined))
            WifiJoinState.Unavailable -> StatusChip(StatusLevel.Error, stringResource(R.string.connect_wifi_failed))
            WifiJoinState.Idle -> Unit
        }
        if (permanentlyDenied && !granted) {
            AdosButton(
                label = stringResource(R.string.connect_open_settings),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AdosButton(
                label = stringResource(R.string.connect_wifi_join),
                onClick = { if (granted) vm.joinGroundStationWifi() else launcher.launch(permission) },
                enabled = state.wifiJoin != WifiJoinState.Requesting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.wifiJoin == WifiJoinState.Joined) {
            AdosButton(
                label = stringResource(R.string.connect_wifi_find),
                onClick = vm::back,
                variant = AdosButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The busy row's words: looking for a linked node, connecting an endpoint, or pairing. */
internal fun busyTextRes(state: ConnectUiState): Int = when {
    state.searching -> R.string.connect_busy_searching
    state.page == ConnectPage.Network -> R.string.connect_busy_connecting
    else -> R.string.connect_busy_pairing
}

/** Words for a discovered agent's advertised profile; null for one this app does not know. */
internal fun discoveredProfileRes(wire: String): Int? =
    LanNodeAssembler.profile(wire).takeIf { it != NodeProfile.Unknown }?.let(::profileLabel)
