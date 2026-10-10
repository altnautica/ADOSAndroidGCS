package com.altnautica.gcs.feature.connect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.transport.UsbSerialTransport
import com.altnautica.gcs.core.model.DeviceId

/**
 * Connect: add a node by LAN discovery, pairing code, manual host, USB flight
 * controller, UDP/TCP endpoint, ground-station Wi-Fi, or the demo vehicle.
 */
@Composable
fun ConnectRoute(
    deepLinkCode: String?,
    deepLinkHost: String?,
    onBack: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenPhoneRadio: () -> Unit,
    onConnected: (DeviceId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var linkApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(deepLinkCode, deepLinkHost) {
        if (!linkApplied) {
            linkApplied = true
            viewModel.applyDeepLink(deepLinkCode, deepLinkHost)
        }
    }
    LaunchedEffect(state.connected) {
        state.connected?.let {
            viewModel.consumeConnected()
            onConnected(it)
        }
    }
    ConnectScreen(state = state, vm = viewModel, onBack = onBack, onOpenCloud = onOpenCloud, onOpenPhoneRadio = onOpenPhoneRadio, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectScreen(state: ConnectUiState, vm: ConnectViewModel, onBack: () -> Unit, onOpenCloud: () -> Unit, onOpenPhoneRadio: () -> Unit, modifier: Modifier) {
    BackHandler(enabled = state.page != ConnectPage.Home) { vm.back() }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(pageTitle(state.page))) },
                navigationIcon = {
                    IconButton(onClick = { if (state.page == ConnectPage.Home) onBack() else vm.back() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.connect_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { ErrorBanner(it) }
            when (state.page) {
                ConnectPage.Home -> HomePage(state, vm, onOpenCloud, onOpenPhoneRadio)
                ConnectPage.Code -> CodePage(state, vm)
                ConnectPage.ManualHost -> HostPage(state, vm)
                ConnectPage.Usb -> UsbPage(state, vm)
                ConnectPage.Network -> NetworkPage(state, vm)
                ConnectPage.WifiJoin -> WifiJoinPage(state, vm)
            }
        }
    }
}

private fun pageTitle(page: ConnectPage): Int = when (page) {
    ConnectPage.Home -> R.string.connect_title
    ConnectPage.Code -> R.string.connect_code
    ConnectPage.ManualHost -> R.string.connect_manual_host
    ConnectPage.Usb -> R.string.connect_usb
    ConnectPage.Network -> R.string.connect_network
    ConnectPage.WifiJoin -> R.string.connect_wifi
}

@Composable
private fun ErrorBanner(error: ConnectError) {
    val text = when (error) {
        ConnectError.CodeNotFound -> stringResource(R.string.connect_error_code_not_found)
        ConnectError.AlreadyPaired -> stringResource(R.string.connect_error_already_paired)
        ConnectError.Unauthorized -> stringResource(R.string.connect_error_unauthorized)
        ConnectError.Unreachable -> stringResource(R.string.connect_error_unreachable)
        ConnectError.InvalidAnswer -> stringResource(R.string.connect_error_invalid)
        ConnectError.CredentialStoreUnavailable -> stringResource(R.string.connect_error_credential_store)
        is ConnectError.Other -> error.message ?: stringResource(R.string.connect_error_other)
    }
    StatusChip(StatusLevel.Error, text, modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2))
}

@Composable
private fun HomePage(state: ConnectUiState, vm: ConnectViewModel, onOpenCloud: () -> Unit, onOpenPhoneRadio: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = AdosSpacing.S8)) {
        item { SectionHeader(stringResource(R.string.connect_discovered)) }
        if (state.discovered.isEmpty()) {
            item {
                Row(Modifier.padding(AdosSpacing.S4), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(AdosSize.IconMd), strokeWidth = AdosSpacing.S1 / 2)
                    Text(
                        stringResource(R.string.connect_searching),
                        modifier = Modifier.padding(start = AdosSpacing.S3),
                        color = AdosTheme.colors.fgSecondary,
                    )
                }
            }
        }
        items(state.discovered, key = { it.agent.deviceId ?: it.agent.serviceName }) { row ->
            val summary = listOfNotNull(
                row.agent.profile,
                row.agent.host,
                when {
                    row.haveKey -> stringResource(R.string.connect_paired_here)
                    row.agent.paired == true -> stringResource(R.string.connect_paired_elsewhere)
                    else -> stringResource(R.string.connect_ready_to_pair)
                },
            ).joinToString(" · ")
            OptionRow(Icons.Outlined.Router, row.agent.name ?: row.agent.deviceId ?: row.agent.serviceName, summary, enabled = !state.busy) {
                vm.pairDiscovered(row)
            }
        }
        item { SectionHeader(stringResource(R.string.connect_other_ways)) }
        item { OptionRow(Icons.Outlined.Password, stringResource(R.string.connect_code), stringResource(R.string.connect_code_summary)) { vm.open(ConnectPage.Code) } }
        item { OptionRow(Icons.Outlined.Dns, stringResource(R.string.connect_manual_host), stringResource(R.string.connect_manual_host_summary)) { vm.open(ConnectPage.ManualHost) } }
        item { OptionRow(Icons.Outlined.Usb, stringResource(R.string.connect_usb), stringResource(R.string.connect_usb_summary)) { vm.open(ConnectPage.Usb) } }
        item { OptionRow(Icons.Outlined.Lan, stringResource(R.string.connect_network), stringResource(R.string.connect_network_summary)) { vm.open(ConnectPage.Network) } }
        item { OptionRow(Icons.Outlined.Wifi, stringResource(R.string.connect_wifi), stringResource(R.string.connect_wifi_summary)) { vm.open(ConnectPage.WifiJoin) } }
        item { OptionRow(Icons.Outlined.Cloud, stringResource(R.string.connect_cloud), stringResource(R.string.connect_cloud_summary), onClick = onOpenCloud) }
        item { OptionRow(Icons.Outlined.SettingsInputAntenna, stringResource(R.string.connect_phone_radio), stringResource(R.string.connect_phone_radio_summary), onClick = onOpenPhoneRadio) }
        item {
            OptionRow(
                Icons.Outlined.Science,
                stringResource(R.string.connect_demo),
                stringResource(if (state.demoEnabled) R.string.connect_demo_on else R.string.connect_demo_summary),
                onClick = vm::enableDemo,
            )
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, summary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AdosTheme.touchMin)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accentPrimary)
        Column(Modifier.weight(1f).padding(start = AdosSpacing.S4)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.fgSecondary)
    }
}

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(AdosSpacing.S4),
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
private fun CodePage(state: ConnectUiState, vm: ConnectViewModel) = FormColumn {
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
    AdosButton(stringResource(R.string.connect_pair), onClick = vm::submitCode, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun HostPage(state: ConnectUiState, vm: ConnectViewModel) = FormColumn {
    Text(stringResource(R.string.connect_host_help), color = AdosTheme.colors.fgSecondary)
    OutlinedTextField(
        value = state.host,
        onValueChange = vm::setHost,
        label = { Text(stringResource(R.string.connect_host_field)) },
        placeholder = { Text("192.168.1.50") },
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
    AdosButton(
        label = stringResource(if (state.apiKey.isBlank()) R.string.connect_pair else R.string.connect_add_with_key),
        onClick = vm::submitHost,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UsbPage(state: ConnectUiState, vm: ConnectViewModel) {
    LaunchedEffect(Unit) { vm.refreshUsb() }
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkPage(state: ConnectUiState, vm: ConnectViewModel) = FormColumn {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = state.udp, onClick = { vm.setUdp(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("UDP") }
        SegmentedButton(selected = !state.udp, onClick = { vm.setUdp(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("TCP") }
    }
    if (state.udp) {
        Text(stringResource(R.string.connect_udp_help), color = AdosTheme.colors.fgSecondary)
        OutlinedTextField(
            value = state.udpPort,
            onValueChange = vm::setUdpPort,
            label = { Text(stringResource(R.string.connect_listen_port)) },
            singleLine = true,
            isError = state.portError != null,
            supportingText = state.portError?.let { { Text(inputErrorText(it)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(stringResource(R.string.connect_tcp_help), color = AdosTheme.colors.fgSecondary)
        OutlinedTextField(
            value = state.tcpHost,
            onValueChange = vm::setTcpHost,
            label = { Text(stringResource(R.string.connect_host_field)) },
            placeholder = { Text("192.168.1.50") },
            singleLine = true,
            isError = state.tcpHostError != null,
            supportingText = state.tcpHostError?.let { { Text(inputErrorText(it)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.tcpPort,
            onValueChange = vm::setTcpPort,
            label = { Text(stringResource(R.string.connect_port)) },
            singleLine = true,
            isError = state.portError != null,
            supportingText = state.portError?.let { { Text(inputErrorText(it)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    AdosButton(stringResource(R.string.connect_add), onClick = vm::submitNetwork, modifier = Modifier.fillMaxWidth())
}

/** Runtime permission the ground-station Wi-Fi join needs. */
private fun wifiJoinPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

@Composable
private fun WifiJoinPage(state: ConnectUiState, vm: ConnectViewModel) {
    val context = LocalContext.current
    val permission = wifiJoinPermission()
    var granted by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
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
            // Rationale before the system prompt (step: nearby Wi-Fi / location only on Wi-Fi join).
            StatusChip(
                StatusLevel.Info,
                stringResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.connect_wifi_rationale_nearby else R.string.connect_wifi_rationale_location),
            )
        }
        if (denied) StatusChip(StatusLevel.Warning, stringResource(R.string.connect_wifi_denied))
        if (state.wifiJoined) StatusChip(StatusLevel.Success, stringResource(R.string.connect_wifi_joined))
        AdosButton(
            label = stringResource(R.string.connect_wifi_join),
            onClick = { if (granted) vm.joinGroundStationWifi() else launcher.launch(permission) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.wifiJoined) {
            AdosButton(
                label = stringResource(R.string.connect_wifi_find),
                onClick = vm::back,
                variant = AdosButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
