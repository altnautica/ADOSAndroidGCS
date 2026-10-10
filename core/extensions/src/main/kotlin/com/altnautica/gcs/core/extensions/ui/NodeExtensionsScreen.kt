package com.altnautica.gcs.core.extensions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.extensions.R
import com.altnautica.gcs.core.extensions.data.ExtensionFailure
import com.altnautica.gcs.core.extensions.data.ExtensionsState
import com.altnautica.gcs.core.extensions.manifest.CatalogEntry
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetail
import com.altnautica.gcs.core.model.DeviceId

/** Node detail "Extensions" page: installed extensions, permissions, parameters, catalog. */
@Composable
fun NodeExtensionsScreen(node: DeviceId, modifier: Modifier = Modifier) {
    val vm: NodeExtensionsViewModel = hiltViewModel(key = "node-extensions:${node.value}")
    LaunchedEffect(vm, node) { vm.start(node) }
    val state by vm.state.collectAsStateWithLifecycle()
    NodeExtensionsContent(
        node = node,
        state = state,
        onRetry = vm::refresh,
        onEnabled = vm::setEnabled,
        onGrant = vm::setGranted,
        onUninstall = vm::uninstall,
        onInstall = vm::install,
        modifier = modifier,
    )
}

@Composable
internal fun NodeExtensionsContent(
    node: DeviceId,
    state: NodeExtensionsUiState,
    onRetry: () -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onGrant: (String, String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onInstall: (CatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val ext = state.extensions) {
        ExtensionsState.Loading -> LoadingState(stringResource(R.string.extensions_loading), modifier)
        is ExtensionsState.NoAgentPath -> EmptyState(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.extensions_no_agent_title),
            message = noPathText(ext.reasonKey),
            modifier = modifier,
        )
        is ExtensionsState.Failed -> when (ext.failure) {
            ExtensionFailure.Unauthorized, ExtensionFailure.PinRequired -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.extensions_denied_title),
                message = failureText(ext.failure),
                modifier = modifier,
            )
            ExtensionFailure.FeatureAbsent -> EmptyState(
                icon = Icons.Outlined.Extension,
                title = stringResource(R.string.extensions_title),
                message = failureText(ext.failure),
                modifier = modifier,
            )
            else -> ErrorState(failureText(ext.failure), stringResource(R.string.extensions_retry), onRetry, modifier)
        }
        is ExtensionsState.Loaded -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.lastError?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            if (ext.extensions.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Extension,
                        title = stringResource(R.string.extensions_empty_title),
                        message = stringResource(R.string.extensions_empty_message),
                    )
                }
            }
            items(ext.extensions, key = { it.pluginId }) { e ->
                ExtensionCard(node, e, state.busy, onEnabled, onGrant, onUninstall)
            }
            item { SectionHeader(stringResource(R.string.extensions_catalog)) }
            state.install?.let { p ->
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.extensions_installing, p.stage ?: p.entry.name))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
            val catalog = state.catalog
            if (catalog != null && catalog.isEmpty()) {
                item { Text(stringResource(R.string.extensions_catalog_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(catalog.orEmpty(), key = { "catalog:" + it.id }) { entry ->
                CatalogRow(entry, enabled = state.install == null && entry.downloadUrl != null, onInstall)
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    node: DeviceId,
    e: ExtensionDetail,
    busy: Set<String>,
    onEnabled: (String, Boolean) -> Unit,
    onGrant: (String, String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
) {
    var expanded by remember(e.pluginId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    stringResource(R.string.extensions_version, e.version),
                    e.risk?.let { stringResource(R.string.extensions_risk, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchSettingRow(
                title = stringResource(R.string.extensions_enabled),
                checked = e.status.isEnabled,
                onCheckedChange = { onEnabled(e.pluginId, it) },
                enabled = e.pluginId !in busy,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.extensions_settings))
            }
            if (expanded) {
                SectionHeader(stringResource(R.string.extensions_permissions))
                val grants = e.permissions.associate { it.id to it.granted }
                val declared = e.declaredPermissions.ifEmpty {
                    e.permissions.map { com.altnautica.gcs.core.extensions.manifest.DeclaredPermission(it.id, false, null, null, null) }
                }
                for (perm in declared) {
                    SwitchSettingRow(
                        title = perm.label ?: perm.id,
                        checked = grants[perm.id] == true,
                        onCheckedChange = { onGrant(e.pluginId, perm.id, it) },
                        summary = listOfNotNull(
                            perm.description,
                            if (perm.required) stringResource(R.string.extensions_permission_required) else null,
                        ).joinToString(" · ").ifEmpty { null },
                        enabled = "${e.pluginId}/${perm.id}" !in busy,
                    )
                }
                val params = e.contributions.parameters
                if (params.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.extensions_parameters))
                    ExtensionParametersPanel(node, e.pluginId, params, sectionKey = "parameters")
                }
                for (section in e.contributions.settings.sortedBy { it.order ?: Double.MAX_VALUE }) {
                    if (section.parameters.isEmpty()) continue
                    SectionHeader(displayLabel(section.title, section.id))
                    ExtensionParametersPanel(node, e.pluginId, section.parameters, sectionKey = "settings:" + section.id)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { onUninstall(e.pluginId) }, enabled = e.pluginId !in busy) {
                        Text(stringResource(R.string.extensions_uninstall))
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(entry: CatalogEntry, enabled: Boolean, onInstall: (CatalogEntry) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            entry.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = { onInstall(entry) }, enabled = enabled) {
            Text(stringResource(R.string.extensions_install))
        }
    }
}
