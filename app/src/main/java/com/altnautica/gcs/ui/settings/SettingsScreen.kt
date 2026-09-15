package com.altnautica.gcs.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.BuildConfig
import com.altnautica.gcs.R
import com.altnautica.gcs.data.settings.BaseUrlProvider
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.SurfaceVariant

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val mapProvider by viewModel.mapProvider.collectAsStateWithLifecycle()
    val hudEnabled by viewModel.hudEnabled.collectAsStateWithLifecycle()
    val compassEnabled by viewModel.compassEnabled.collectAsStateWithLifecycle()
    val altLadderEnabled by viewModel.altLadderEnabled.collectAsStateWithLifecycle()
    val speedLadderEnabled by viewModel.speedLadderEnabled.collectAsStateWithLifecycle()
    val wfbChannel by viewModel.wfbChannel.collectAsStateWithLifecycle()
    val wfbBandwidth by viewModel.wfbBandwidth.collectAsStateWithLifecycle()
    val groundStationBaseUrl by viewModel.groundStationBaseUrl.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(20.dp))

        // Theme
        SettingsSection(title = stringResource(R.string.settings_theme)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeOption.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = theme == option,
                        onClick = { viewModel.setTheme(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeOption.entries.size,
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        }

        // Units
        SettingsSection(title = stringResource(R.string.settings_units)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                UnitSystem.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = units == option,
                        onClick = { viewModel.setUnits(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = UnitSystem.entries.size,
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        }

        // Map provider
        SettingsSection(title = stringResource(R.string.settings_map_provider)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MapProvider.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mapProvider == option,
                        onClick = { viewModel.setMapProvider(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = MapProvider.entries.size,
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        }

        // HUD elements
        SettingsSection(title = stringResource(R.string.settings_hud)) {
            ToggleRow(stringResource(R.string.settings_hud_overlay), hudEnabled) {
                viewModel.setHudEnabled(it)
            }
            ToggleRow(stringResource(R.string.settings_hud_compass), compassEnabled) {
                viewModel.setCompassEnabled(it)
            }
            ToggleRow(stringResource(R.string.settings_hud_alt_ladder), altLadderEnabled) {
                viewModel.setAltLadderEnabled(it)
            }
            ToggleRow(stringResource(R.string.settings_hud_speed_ladder), speedLadderEnabled) {
                viewModel.setSpeedLadderEnabled(it)
            }
        }

        // Agent address, pairing, AP credential and the USB-serial link. Order
        // matters: the address decides which node the pairing calls reach.
        SettingsSection(title = stringResource(R.string.settings_ground_station)) {
            GroundStationUrlField(
                current = groundStationBaseUrl,
                onSave = { url, callback -> viewModel.setGroundStationBaseUrl(url, callback) },
            )
        }

        PairingSection()

        GroundStationApSection()

        UsbSerialSettings()

        // WFB-ng Video Link
        SettingsSection(title = stringResource(R.string.settings_wfb_link)) {
            WfbChannelDropdown(
                selected = wfbChannel,
                onSelected = { viewModel.setWfbChannel(it) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_bandwidth),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                WfbBandwidth.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = wfbBandwidth == option,
                        onClick = { viewModel.setWfbBandwidth(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = WfbBandwidth.entries.size,
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // About
        SettingsSection(title = stringResource(R.string.settings_about)) {
            InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
            InfoRow(stringResource(R.string.settings_license), "GPL-3.0")
            InfoRow(stringResource(R.string.settings_website), "altnautica.com")
            InfoRow(stringResource(R.string.settings_source), "github.com/altnautica/ADOSAndroidGCS")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = ElectricBlue,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GroundStationUrlField(
    current: String,
    onSave: (String, (Boolean) -> Unit) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(draft) {
        // Clear status whenever the user types again.
        if (saved) saved = false
    }

    Text(
        text = stringResource(R.string.settings_base_url),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            error = null
        },
        singleLine = true,
        isError = error != null,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(BaseUrlProvider.DEFAULT_BASE_URL) },
    )
    val messageRes = error ?: if (saved) R.string.settings_base_url_saved else null
    if (messageRes != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                if (!BaseUrlProvider.isValidBaseUrl(draft)) {
                    error = R.string.settings_base_url_invalid
                    return@Button
                }
                onSave(draft) { ok ->
                    if (ok) {
                        saved = true
                        error = null
                    } else {
                        error = R.string.settings_base_url_save_failed
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        ) {
            Text(stringResource(R.string.save))
        }
        OutlinedButton(
            onClick = {
                draft = BaseUrlProvider.DEFAULT_BASE_URL
                error = null
                saved = false
            },
        ) {
            Text(stringResource(R.string.reset))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WfbChannelDropdown(
    selected: WfbChannel,
    onSelected: (WfbChannel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.settings_wifi_channel),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            WfbChannel.entries.forEach { channel ->
                DropdownMenuItem(
                    text = { Text(channel.label) },
                    onClick = {
                        onSelected(channel)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Node identity and the pairing handshake.
 *
 * A paired agent refuses every data route to a caller with no key, so without
 * this section the app worked only against an unpaired node and every other
 * screen failed generically. The claim is the same local contract the web GCS
 * uses: probe the node, claim it while unclaimed, keep the returned key.
 */
@Composable
private fun PairingSection(viewModel: PairingViewModel = hiltViewModel()) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    val reachable by viewModel.reachable.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val reportedReach by viewModel.pairedMdnsHost.collectAsStateWithLifecycle()

    SettingsSection(title = stringResource(R.string.pairing_section_title)) {
        Text(
            text = stringResource(viewModel.statusLabel(info, hasKey, reachable)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.pairing_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        info?.let { probe ->
            Spacer(Modifier.height(8.dp))
            InfoRow(stringResource(R.string.pairing_device_name), probe.name)
            InfoRow(stringResource(R.string.pairing_device_id), probe.deviceId)
            InfoRow(stringResource(R.string.pairing_profile), probe.profile)
        }

        if (!hasKey) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.pairing_key_absent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.probe() }, enabled = !busy) {
                Text(stringResource(R.string.pairing_action_probe))
            }
            if (hasKey) {
                Button(
                    onClick = { viewModel.unpair() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                ) {
                    Text(stringResource(R.string.pairing_action_unpair))
                }
            } else {
                Button(
                    onClick = { viewModel.pair() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                ) {
                    Text(stringResource(R.string.pairing_action_pair))
                }
            }
        }

        // The node reports a reach it has proven resolvable; offer it rather
        // than adopting it silently, since the operator may be reaching this
        // node by IP on purpose.
        val reach = reportedReach
        if (hasKey && !reach.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            InfoRow(stringResource(R.string.pairing_reach), reach)
            OutlinedButton(onClick = { viewModel.useReportedReach() }) {
                Text(stringResource(R.string.pairing_action_use_reach))
            }
        }

        if (hasKey) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { viewModel.forgetLocalKey() }) {
                Text(stringResource(R.string.pairing_action_forget))
            }
        }

        message?.let { res ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LaunchedEffect(res) {
                kotlinx.coroutines.delay(6000)
                viewModel.consumeMessage()
            }
        }
    }
}

/**
 * Prompt for this unit's ground-station AP passphrase.
 *
 * Explicitly a prompt: the agent generates the passphrase per unit, so there is
 * no constant to attempt. A compiled-in default would either publish one unit's
 * credential or fail to associate with every other unit.
 */
@Composable
private fun GroundStationApSection(viewModel: PairingViewModel = hiltViewModel()) {
    val stored by viewModel.apPassphraseStored.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }

    SettingsSection(title = stringResource(R.string.station_ap_title)) {
        Text(
            text = stringResource(R.string.station_ap_prompt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            label = { Text(stringResource(R.string.station_ap_passphrase_label)) },
            supportingText = { Text(stringResource(R.string.station_ap_qr_label)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.saveApCredential(draft)
                    draft = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            ) {
                Text(stringResource(R.string.save))
            }
            OutlinedButton(onClick = { viewModel.joinGroundStationAp() }, enabled = stored) {
                Text(stringResource(R.string.station_ap_join))
            }
            if (stored) {
                OutlinedButton(onClick = { viewModel.forgetApCredential() }) {
                    Text(stringResource(R.string.station_ap_forget))
                }
            }
        }
    }
}
