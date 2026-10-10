package com.altnautica.gcs.feature.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.EmptyStateAction
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.extensions.manifest.ExtensionSlots
import com.altnautica.gcs.core.extensions.ui.ExtensionNodePage
import com.altnautica.gcs.core.extensions.ui.rememberExtensionNodePages
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.feature.configure.params.ParametersRoute
import com.altnautica.gcs.feature.configure.setup.SetupRoute
import com.altnautica.gcs.feature.fleet.FleetViewModel
import com.altnautica.gcs.feature.fleet.data.NodeVitals
import com.altnautica.gcs.feature.fleet.ui.LivenessChip
import com.altnautica.gcs.feature.fleet.ui.ReachBadge
import com.altnautica.gcs.feature.fleet.ui.profileLabel
import com.altnautica.gcs.feature.node.agent.AgentTab
import com.altnautica.gcs.feature.node.groundstation.GroundStationTab
import com.altnautica.gcs.feature.node.logs.NodeLogsTab
import com.altnautica.gcs.feature.node.overview.OverviewTab

/**
 * Node detail: header (profile, liveness, reach, vitals) and the tab set for
 * the node's profile, plus any extension `node.detail.tab` pages.
 */
@Composable
fun NodeDetailRoute(
    nodeId: DeviceId,
    onBack: () -> Unit,
    onOpenCockpit: (DeviceId) -> Unit,
    onOpenNode: (DeviceId) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = hiltViewModel<NodeDetailViewModel, NodeDetailViewModel.Factory>(key = "node-${nodeId.value}") { it.create(nodeId.value) }
    val state by vm.state.collectAsStateWithLifecycle()
    val forget by vm.forget.collectAsStateWithLifecycle()
    LaunchedEffect(forget) { if (forget == ForgetState.Done) onBack() }
    NodeDetailScreen(
        nodeId = nodeId,
        state = state,
        forget = forget,
        onBack = onBack,
        onOpenCockpit = onOpenCockpit,
        onOpenNode = onOpenNode,
        onPair = onPair,
        onUnpair = vm::unpair,
        onForget = vm::forgetLocally,
        onClearForget = vm::clearForget,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    nodeId: DeviceId,
    state: NodeDetailState,
    forget: ForgetState,
    onBack: () -> Unit,
    onOpenCockpit: (DeviceId) -> Unit,
    onOpenNode: (DeviceId) -> Unit,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onForget: () -> Unit,
    onClearForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? NodeDetailState.Ready
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(ready?.node?.name ?: nodeId.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.node_back))
                    }
                },
                actions = {
                    if (ready != null && NodeTabs.hasAgentLink(ready.node)) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.node_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.node_unpair)) },
                                    leadingIcon = { Icon(Icons.Outlined.LinkOff, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        confirmUnpair = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                NodeDetailState.Loading -> LoadingState(stringResource(R.string.node_loading), Modifier.align(Alignment.Center))
                NodeDetailState.Missing -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    title = stringResource(R.string.node_missing_title),
                    message = stringResource(R.string.node_missing_message),
                    action = EmptyStateAction(stringResource(R.string.node_back), onBack),
                    modifier = Modifier.align(Alignment.Center),
                )
                is NodeDetailState.Ready -> if (state.node.reaches.singleOrNull() == Reach.Blocked(BlockReason.NotPaired)) {
                    EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.node_unpaired_title),
                        message = stringResource(R.string.node_unpaired_message),
                        action = EmptyStateAction(stringResource(R.string.node_pair), onPair),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    NodeBody(state.node, state.tabs, state.vitals, onOpenCockpit, onOpenNode)
                }
            }
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.node_unpair_title)) },
            text = { Text(stringResource(R.string.node_unpair_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text(stringResource(R.string.node_unpair)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
    if (forget is ForgetState.Failed) {
        AlertDialog(
            onDismissRequest = onClearForget,
            title = { Text(stringResource(R.string.node_unpair_failed_title)) },
            text = { Text(stringResource(R.string.node_unpair_failed_message)) },
            confirmButton = { TextButton(onClick = onForget) { Text(stringResource(R.string.node_forget)) } },
            dismissButton = { TextButton(onClick = onClearForget) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}

/** Builtin tab or an extension page in the tab row. */
private sealed interface TabItem {
    val key: String

    data class Builtin(val tab: NodeTab) : TabItem {
        override val key: String get() = tab.name
    }

    data class Extension(val page: com.altnautica.gcs.core.extensions.host.SlotMount) : TabItem {
        override val key: String get() = page.key
    }
}

@Composable
private fun NodeBody(
    node: NodeSummary,
    tabs: List<NodeTab>,
    vitals: NodeVitals,
    onOpenCockpit: (DeviceId) -> Unit,
    onOpenNode: (DeviceId) -> Unit,
) {
    val extensionPages by rememberExtensionNodePages(node.id, ExtensionSlots.NODE_DETAIL_TAB)
    val items: List<TabItem> = tabs.map { TabItem.Builtin(it) } +
        if (NodeTabs.hasAgentLink(node)) extensionPages.map { TabItem.Extension(it) } else emptyList()
    var selectedKey by rememberSaveable(node.id.value) { mutableStateOf(NodeTab.Overview.name) }
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val selected = items.getOrNull(selectedIndex)

    Column(Modifier.fillMaxSize()) {
        Header(node, vitals)
        PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = AdosSpacing.S4) {
            items.forEach { item ->
                Tab(
                    selected = item.key == selected?.key,
                    onClick = { selectedKey = item.key },
                    text = {
                        Text(
                            when (item) {
                                is TabItem.Builtin -> stringResource(tabLabel(item.tab))
                                is TabItem.Extension -> item.page.contribution.title ?: item.page.pluginName
                            },
                        )
                    },
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (selected) {
                null -> Unit
                is TabItem.Extension -> ExtensionNodePage(node = node.id, page = selected.page, modifier = Modifier.fillMaxSize())
                is TabItem.Builtin -> when (val tab = selected.tab) {
                    NodeTab.Overview -> OverviewTab(node.id.value)
                    NodeTab.Cockpit -> CockpitLauncher(node, onOpenCockpit)
                    NodeTab.Setup -> SetupRoute(nodeId = node.id, fcVariant = node.fcVariant)
                    NodeTab.Parameters -> ParametersRoute(nodeId = node.id)
                    NodeTab.CarriedDrones, NodeTab.Radio, NodeTab.Network, NodeTab.Mesh ->
                        GroundStationTab(node.id.value, tab, onOpenNode)
                    NodeTab.Logs -> NodeLogsTab(node.id.value)
                    NodeTab.Agent -> AgentTab(node.id.value)
                }
            }
        }
    }
}

@Composable
private fun Header(node: NodeSummary, vitals: NodeVitals) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        StatusChip(StatusLevel.Neutral, stringResource(profileLabel(node.profile)))
        LivenessChip(node.liveness)
        ReachBadge(node.reaches.firstOrNull())
        vitals.batteryPct?.let { StatusChip(StatusLevel.Neutral, stringResource(R.string.node_battery, it)) }
        vitals.modeName?.let { StatusChip(StatusLevel.Neutral, it) }
    }
}

/** The Cockpit tab: the cockpit is full screen, so the tab launches it. */
@Composable
private fun CockpitLauncher(node: NodeSummary, onOpenCockpit: (DeviceId) -> Unit) {
    val card = FleetViewModel.card(node, NodeVitals.UNKNOWN)
    Box(Modifier.fillMaxSize().padding(AdosSpacing.S6), contentAlignment = Alignment.Center) {
        if (node.profile == NodeProfile.GroundStation && node.carriedDroneId == null) {
            EmptyState(
                icon = Icons.Outlined.LinkOff,
                title = stringResource(R.string.node_cockpit_no_drone_title),
                message = stringResource(R.string.node_cockpit_no_drone_message),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
                Text(
                    if (node.profile == NodeProfile.GroundStation) {
                        stringResource(R.string.node_cockpit_carried, node.carriedDroneId?.value.orEmpty())
                    } else {
                        stringResource(R.string.node_cockpit_message)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                AdosButton(
                    label = stringResource(R.string.node_open_cockpit),
                    onClick = { onOpenCockpit(node.id) },
                    enabled = card.canFly,
                    icon = Icons.Outlined.SportsEsports,
                )
            }
        }
    }
}

private fun tabLabel(tab: NodeTab): Int = when (tab) {
    NodeTab.Overview -> R.string.tab_overview
    NodeTab.Cockpit -> R.string.tab_cockpit
    NodeTab.Setup -> R.string.tab_setup
    NodeTab.Parameters -> R.string.tab_parameters
    NodeTab.CarriedDrones -> R.string.tab_carried_drones
    NodeTab.Radio -> R.string.tab_radio
    NodeTab.Network -> R.string.tab_network
    NodeTab.Mesh -> R.string.tab_mesh
    NodeTab.Logs -> R.string.tab_logs
    NodeTab.Agent -> R.string.tab_agent
}
