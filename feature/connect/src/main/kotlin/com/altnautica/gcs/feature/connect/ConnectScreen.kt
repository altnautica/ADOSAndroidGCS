package com.altnautica.gcs.feature.connect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.console.ConsoleTwoPane
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId

/**
 * Add vehicle: LAN discovery, pairing code, manual host, USB flight controller,
 * UDP/TCP endpoint, ground-station Wi-Fi, cloud, phone radio, or the demo vehicle.
 * Landscape shows the methods beside the selected method; portrait keeps one page at a time.
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

@Composable
private fun ConnectScreen(state: ConnectUiState, vm: ConnectViewModel, onBack: () -> Unit, onOpenCloud: () -> Unit, onOpenPhoneRadio: () -> Unit, modifier: Modifier) {
    BackHandler(enabled = state.page != ConnectPage.Home) { vm.back() }
    val landscape = isLandscape()
    val title = if (landscape || state.page == ConnectPage.Home) R.string.connect_title else pageTitle(state.page)
    ConsoleScaffold(
        title = stringResource(title),
        onBack = { if (state.page == ConnectPage.Home) onBack() else vm.back() },
        modifier = modifier,
        hints = listOf(
            Hint(ConsoleButton.Confirm, stringResource(R.string.connect_hint_select)),
            Hint(ConsoleButton.Back, stringResource(R.string.connect_back)),
        ),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { ErrorBanner(it) }
            ConsoleTwoPane(
                list = {
                    if (landscape) {
                        MethodList(state, vm, onOpenCloud, onOpenPhoneRadio)
                    } else {
                        PortraitHome(state, vm, onOpenCloud, onOpenPhoneRadio)
                    }
                },
                detail = {
                    when (state.page) {
                        ConnectPage.Home -> LazyColumn(contentPadding = PaddingValues(bottom = AdosSpacing.S8)) { discoveredItems(state, vm) }
                        ConnectPage.Code -> CodePage(state, vm)
                        ConnectPage.ManualHost -> HostPage(state, vm)
                        ConnectPage.Usb -> UsbPage(state, vm)
                        ConnectPage.Network -> NetworkPage(state, vm)
                        ConnectPage.WifiJoin -> WifiJoinPage(state, vm)
                    }
                },
                showDetailInPortrait = state.page != ConnectPage.Home,
            )
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

/** Portrait start page: discovered nodes first, then every other method. */
@Composable
private fun PortraitHome(state: ConnectUiState, vm: ConnectViewModel, onOpenCloud: () -> Unit, onOpenPhoneRadio: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = AdosSpacing.S8)) {
        discoveredItems(state, vm)
        item { SectionHeader(stringResource(R.string.connect_other_ways)) }
        methodItems(state, vm, onOpenCloud, onOpenPhoneRadio)
    }
}

/** Landscape list pane: the methods, with Discovered and its live count on top. */
@Composable
private fun MethodList(state: ConnectUiState, vm: ConnectViewModel, onOpenCloud: () -> Unit, onOpenPhoneRadio: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = AdosSpacing.S2)) {
        item {
            OptionRow(
                icon = Icons.Outlined.Radar,
                title = stringResource(R.string.connect_discovered),
                summary = pluralStringResource(R.plurals.connect_discovered_count, state.discovered.size, state.discovered.size),
                selected = state.page == ConnectPage.Home,
                onClick = vm::back,
            )
        }
        methodItems(state, vm, onOpenCloud, onOpenPhoneRadio)
    }
}

private fun LazyListScope.methodItems(state: ConnectUiState, vm: ConnectViewModel, onOpenCloud: () -> Unit, onOpenPhoneRadio: () -> Unit) {
    item { MethodRow(Icons.Outlined.Password, R.string.connect_code, R.string.connect_code_summary, state.page == ConnectPage.Code) { vm.open(ConnectPage.Code) } }
    item { MethodRow(Icons.Outlined.Dns, R.string.connect_manual_host, R.string.connect_manual_host_summary, state.page == ConnectPage.ManualHost) { vm.open(ConnectPage.ManualHost) } }
    item { MethodRow(Icons.Outlined.Usb, R.string.connect_usb, R.string.connect_usb_summary, state.page == ConnectPage.Usb) { vm.open(ConnectPage.Usb) } }
    item { MethodRow(Icons.Outlined.Lan, R.string.connect_network, R.string.connect_network_summary, state.page == ConnectPage.Network) { vm.open(ConnectPage.Network) } }
    item { MethodRow(Icons.Outlined.Wifi, R.string.connect_wifi, R.string.connect_wifi_summary, state.page == ConnectPage.WifiJoin) { vm.open(ConnectPage.WifiJoin) } }
    item { MethodRow(Icons.Outlined.Cloud, R.string.connect_cloud, R.string.connect_cloud_summary, false, onOpenCloud) }
    item { MethodRow(Icons.Outlined.SettingsInputAntenna, R.string.connect_phone_radio, R.string.connect_phone_radio_summary, false, onOpenPhoneRadio) }
    item {
        MethodRow(
            Icons.Outlined.Science,
            R.string.connect_demo,
            if (state.demoEnabled) R.string.connect_demo_on else R.string.connect_demo_summary,
            false,
            vm::enableDemo,
        )
    }
}

private fun LazyListScope.discoveredItems(state: ConnectUiState, vm: ConnectViewModel) {
    item { SectionHeader(stringResource(R.string.connect_discovered)) }
    if (state.discovered.isEmpty()) {
        item { if (state.discoverySlow) DiscoveryHelp(vm::retryDiscovery) else Searching() }
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
}

@Composable
private fun Searching() {
    Row(Modifier.padding(AdosSpacing.S4), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(AdosSize.IconMd), strokeWidth = AdosSpacing.S1 / 2)
        Text(
            stringResource(R.string.connect_searching),
            modifier = Modifier.padding(start = AdosSpacing.S3),
            color = AdosTheme.colors.fgSecondary,
        )
    }
}

@Composable
private fun DiscoveryHelp(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Text(stringResource(R.string.connect_discovery_help), color = AdosTheme.colors.fgSecondary)
        AdosButton(stringResource(R.string.connect_retry), onClick = onRetry, variant = AdosButtonVariant.Secondary)
    }
}

@Composable
private fun MethodRow(icon: ImageVector, title: Int, summary: Int, selected: Boolean, onClick: () -> Unit) =
    OptionRow(icon, stringResource(title), stringResource(summary), selected = selected, onClick = onClick)

@Composable
internal fun OptionRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = AdosTheme.colors
    AdosListItem(
        headline = title,
        onClick = onClick,
        supporting = summary,
        selected = selected,
        enabled = enabled,
        leading = { Icon(icon, contentDescription = null, tint = colors.accentPrimary) },
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.fgSecondary) },
        modifier = Modifier.padding(horizontal = AdosSpacing.S2),
    )
}
