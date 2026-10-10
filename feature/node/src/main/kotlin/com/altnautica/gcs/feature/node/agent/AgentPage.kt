package com.altnautica.gcs.feature.node.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.extensions.host.SlotMount
import com.altnautica.gcs.core.extensions.manifest.ExtensionSlots
import com.altnautica.gcs.core.extensions.ui.ExtensionNodePage
import com.altnautica.gcs.core.extensions.ui.NodeExtensionsScreen
import com.altnautica.gcs.core.extensions.ui.rememberExtensionNodePages
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.ProblemState
import com.altnautica.gcs.feature.node.common.WriteState
import com.altnautica.gcs.feature.node.common.WriteStatus
import com.altnautica.gcs.feature.node.common.arr
import com.altnautica.gcs.feature.node.common.bool
import com.altnautica.gcs.feature.node.common.double
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.fmtInt
import com.altnautica.gcs.feature.node.common.int
import com.altnautica.gcs.feature.node.common.long
import com.altnautica.gcs.feature.node.common.obj
import com.altnautica.gcs.feature.node.common.objects
import com.altnautica.gcs.feature.node.common.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The node's Agent page: the section list, one section's typed form with its
 * live readouts, and "All settings" (the schema-driven config tree scoped to
 * the section).
 */
@Composable
fun AgentTab(nodeId: String, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<AgentPageViewModel, AgentPageViewModel.Factory>(key = "agent-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val write by vm.writeState.collectAsStateWithLifecycle()
    var sectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var treeOpen by rememberSaveable { mutableStateOf(false) }
    var pageKey by rememberSaveable { mutableStateOf<String?>(null) }
    val section = AgentSection.ALL.firstOrNull { it.id == sectionId }
    // Extension agent pages sit in the section they name, else after the built-in sections.
    val pages by rememberExtensionNodePages(DeviceId(nodeId), ExtensionSlots.NODE_AGENT_PAGE)
    val page = pages.firstOrNull { it.key == pageKey }

    BackHandler(enabled = section != null || page != null) {
        when {
            page != null -> pageKey = null
            treeOpen -> treeOpen = false
            else -> sectionId = null
        }
    }
    LaunchedEffect(section) { section?.let(vm::openSection) }

    Column(modifier.fillMaxSize()) {
        WriteStatus(write, vm.writes, Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1))
        when {
            page != null -> {
                SectionTitle(page.contribution.title ?: page.pluginName) { pageKey = null }
                ExtensionNodePage(node = DeviceId(nodeId), page = page, modifier = Modifier.fillMaxSize())
            }
            section == null -> SectionList(
                loosePages = pages.filter { p -> AgentSection.ALL.none { it.id == p.contribution.section } },
                onOpen = { sectionId = it.id },
                onOpenPage = { pageKey = it.key },
            )
            section.isExtensions -> {
                SectionTitle(stringResource(section.titleRes)) { sectionId = null }
                NodeExtensionsScreen(node = DeviceId(nodeId), modifier = Modifier.fillMaxSize())
            }
            treeOpen -> ConfigTreeScreen(
                state = state,
                roots = section.subtreesFor(state.profile),
                busy = write is WriteState.Running,
                onWrite = vm::write,
                onRetry = vm::reload,
                onBack = { treeOpen = false },
            )
            else -> SectionScreen(
                section = section,
                state = state,
                pages = pages.filter { it.contribution.section == section.id },
                busy = write is WriteState.Running,
                onWrite = vm::write,
                onRestart = vm::restartService,
                onRefreshLive = vm::refreshLive,
                onRetry = vm::reload,
                onOpenTree = { treeOpen = true },
                onOpenPage = { pageKey = it.key },
                onBack = { sectionId = null },
            )
        }
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit, subtitle: String? = null) {
    val colors = AdosTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AdosTheme.touchMin)
            .clickable(onClick = onClick)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.fgSecondary)
    }
}

@Composable
private fun SectionList(loosePages: List<SlotMount>, onOpen: (AgentSection) -> Unit, onOpenPage: (SlotMount) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = AdosSpacing.S2)) {
        items(AgentSection.ALL, key = { it.id }) { section ->
            NavRow(stringResource(section.titleRes), onClick = { onOpen(section) })
        }
        items(loosePages, key = { it.key }) { p ->
            NavRow(p.contribution.title ?: p.pluginName, onClick = { onOpenPage(p) }, subtitle = p.pluginName)
        }
    }
}

@Composable
private fun SectionTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = AdosSpacing.S4)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.node_back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionScreen(
    section: AgentSection,
    state: AgentPageUiState,
    pages: List<SlotMount>,
    busy: Boolean,
    onWrite: (String, String) -> Unit,
    onRestart: (String) -> Unit,
    onRefreshLive: (SectionLive) -> Unit,
    onRetry: () -> Unit,
    onOpenTree: () -> Unit,
    onOpenPage: (SlotMount) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SectionTitle(stringResource(section.titleRes), onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AdosSpacing.S8),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            section.live.filter { it != SectionLive.None }.forEach { kind ->
                item(key = "live-$kind") {
                    Box(Modifier.padding(horizontal = AdosSpacing.S4)) {
                        LiveBlock(kind, state.live[kind] ?: LiveState.Loading, busy, onRestart) { onRefreshLive(kind) }
                    }
                }
            }
            items(pages, key = { it.key }) { p ->
                NavRow(p.contribution.title ?: p.pluginName, onClick = { onOpenPage(p) }, subtitle = p.pluginName)
            }
            when (val config = state.config) {
                AgentConfigState.Loading -> item { LoadingState(stringResource(R.string.node_loading)) }
                is AgentConfigState.Problem -> item { ProblemState(config.problem, onRetry) }
                is AgentConfigState.Ready -> {
                    val leaves = section.fieldsFor(state.profile).mapNotNull { config.schema.leaf(it) }
                    if (leaves.isNotEmpty()) item { SectionHeader(stringResource(R.string.agent_settings)) }
                    items(leaves, key = { it.key }) { leaf ->
                        ConfigFieldRow(
                            leaf = leaf,
                            value = AgentPageViewModel.valueAt(config.config, leaf.key),
                            error = state.fieldErrors[leaf.key],
                            enabled = !busy,
                            onCommit = { onWrite(leaf.key, it) },
                        )
                    }
                    item {
                        AdosButton(
                            label = stringResource(R.string.agent_all_settings),
                            onClick = onOpenTree,
                            variant = AdosButtonVariant.Secondary,
                            icon = Icons.Outlined.Tune,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigTreeScreen(
    state: AgentPageUiState,
    roots: List<String>,
    busy: Boolean,
    onWrite: (String, String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = path.isNotEmpty()) { path = parentOf(path, roots) }
    Column(Modifier.fillMaxSize()) {
        val config = state.config
        val group = (config as? AgentConfigState.Ready)?.schema?.let { if (path.isEmpty()) null else it.group(path) }
        SectionTitle(group?.title ?: stringResource(R.string.agent_all_settings)) {
            if (path.isEmpty()) onBack() else path = parentOf(path, roots)
        }
        when (config) {
            AgentConfigState.Loading -> LoadingState(stringResource(R.string.node_loading))
            is AgentConfigState.Problem -> ProblemState(config.problem, onRetry)
            is AgentConfigState.Ready -> {
                val children: List<ConfigNode> = if (path.isEmpty()) {
                    roots.mapNotNull { config.schema.group(it) ?: config.schema.leaf(it) }
                } else {
                    group?.children.orEmpty()
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = AdosSpacing.S8)) {
                    items(children, key = { it.key }) { node ->
                        when (node) {
                            is ConfigNode.Group -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = AdosTheme.touchMin)
                                    .clickable { path = node.key }
                                    .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S3),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(node.title, style = MaterialTheme.typography.titleSmall)
                                    Text(node.key, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
                                }
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                            }
                            is ConfigNode.Leaf -> ConfigFieldRow(
                                leaf = node,
                                value = AgentPageViewModel.valueAt(config.config, node.key),
                                error = state.fieldErrors[node.key],
                                enabled = !busy,
                                onCommit = { onWrite(node.key, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Parent of a tree path; a section root goes back to the section's root list. */
private fun parentOf(path: String, roots: List<String>): String =
    if (path in roots || !path.contains('.')) "" else path.substringBeforeLast('.')

@Composable
private fun LiveBlock(kind: SectionLive, state: LiveState, busy: Boolean, onRestart: (String) -> Unit, onRetry: () -> Unit) {
    val title = stringResource(
        when (kind) {
            SectionLive.SystemResources -> R.string.agent_live_system
            SectionLive.Battery -> R.string.agent_live_battery
            SectionLive.WfbLink -> R.string.agent_live_wfb
            SectionLive.CameraRoster -> R.string.agent_live_cameras
            SectionLive.Services -> R.string.agent_live_services
            SectionLive.None -> R.string.agent_settings
        },
    )
    when (state) {
        LiveState.Loading -> NodeCard(title) { LoadingState(stringResource(R.string.node_loading)) }
        LiveState.Absent -> Unit
        is LiveState.Failed -> NodeCard(title) { ProblemState(state.problem, onRetry) }
        is LiveState.Ready -> NodeCard(title) {
            val o = state.value as? JsonObject ?: JsonObject(emptyMap())
            when (kind) {
                SectionLive.SystemResources -> SystemReadout(o)
                SectionLive.Battery -> BatteryReadout(o)
                SectionLive.WfbLink -> WfbReadout(o)
                SectionLive.CameraRoster -> CameraReadout(o)
                SectionLive.Services -> ServicesReadout(o, busy, onRestart)
                SectionLive.None -> Unit
            }
        }
    }
}

@Composable
private fun SystemReadout(o: JsonObject) {
    if (o.bool("available") == false) {
        KeyValueRow(stringResource(R.string.agent_cpu), null)
        return
    }
    KeyValueRow(stringResource(R.string.agent_cpu), o.double("cpu_percent").fmt(0, " %"))
    KeyValueRow(
        stringResource(R.string.agent_memory),
        o.long("memory_used_mb")?.let { u -> o.long("memory_total_mb")?.let { "$u / $it MB" } ?: "$u MB" },
    )
    KeyValueRow(stringResource(R.string.agent_disk), o.double("disk_percent").fmt(0, " %"))
    o.obj("temperatures")?.forEach { (sensor, v) ->
        KeyValueRow(sensor, (v as? JsonPrimitive)?.content?.toDoubleOrNull().fmt(0, " °C"))
    }
}

@Composable
private fun BatteryReadout(o: JsonObject) {
    val packs = o.arr("packs")?.objects().orEmpty()
    if (o.bool("enabled") == false || packs.isEmpty()) {
        Text(stringResource(R.string.agent_battery_none), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        return
    }
    packs.forEach { p ->
        val id = p.int("id") ?: 0
        KeyValueRow(stringResource(R.string.agent_battery_pack, id), p.int("remaining_pct")?.takeIf { it >= 0 }.fmtInt(" %"))
        KeyValueRow(stringResource(R.string.agent_voltage), p.double("voltage_v").fmt(2, " V"))
        KeyValueRow(stringResource(R.string.agent_min_cell), p.double("min_cell_v").fmt(2, " V"))
        p.obj("prediction")?.long("eta_s")?.let { KeyValueRow(stringResource(R.string.agent_time_to_reserve), "${it / 60} min") }
    }
}

@Composable
private fun WfbReadout(o: JsonObject) {
    KeyValueRow(stringResource(R.string.agent_wfb_state), o.str("state"))
    KeyValueRow(stringResource(R.string.agent_wfb_channel), o.int("actual_channel")?.toString() ?: o.int("channel")?.toString())
    KeyValueRow(stringResource(R.string.agent_wfb_rssi), o.double("rssi_dbm").fmt(0, " dBm"))
    KeyValueRow(stringResource(R.string.agent_wfb_snr), o.double("snr_db").fmt(0, " dB"))
    KeyValueRow(stringResource(R.string.agent_wfb_loss), o.double("loss_percent").fmt(1, " %"))
    KeyValueRow(stringResource(R.string.agent_wfb_bitrate), o.long("bitrate_kbps").fmtInt(" kb/s"))
    KeyValueRow(stringResource(R.string.agent_wfb_tx_power), o.double("tx_power_dbm").fmt(0, " dBm"))
}

@Composable
private fun CameraReadout(o: JsonObject) {
    val cameras = (o["cameras"] as? JsonArray)?.objects().orEmpty()
    if (cameras.isEmpty()) {
        Text(stringResource(R.string.agent_cameras_none), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        return
    }
    cameras.forEach { c ->
        KeyValueRow(c.str("name") ?: c.str("id") ?: "", listOfNotNull(c.str("role"), c.str("source")).joinToString(" · ").ifEmpty { null })
    }
}

@Composable
private fun ServicesReadout(o: JsonObject, busy: Boolean, onRestart: (String) -> Unit) {
    val services = o.arr("services")?.objects().orEmpty()
    if (o.bool("systemd_available") == false || services.isEmpty()) {
        Text(stringResource(R.string.agent_services_none), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        return
    }
    services.forEach { s ->
        val name = s.str("name") ?: return@forEach
        val active = s.bool("active") == true
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                StatusChip(
                    level = if (active) StatusLevel.Success else StatusLevel.Warning,
                    label = listOfNotNull(s.str("state"), s.str("sub_state")).joinToString(" · ").ifEmpty { "—" },
                )
            }
            IconButton(onClick = { onRestart(name) }, enabled = !busy) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.agent_restart, name))
            }
        }
    }
}
