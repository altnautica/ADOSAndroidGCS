package com.altnautica.gcs.feature.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.overlay.AdosDialog
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.link.node.canFly
import com.altnautica.gcs.core.designsystem.component.node.LivenessChip
import com.altnautica.gcs.core.designsystem.component.node.ReachBadge
import com.altnautica.gcs.core.designsystem.component.node.profileLabel
import com.altnautica.gcs.feature.node.agent.AgentTab
import com.altnautica.gcs.feature.node.groundstation.GroundStationTab
import com.altnautica.gcs.feature.node.logs.NodeLogsTab
import com.altnautica.gcs.feature.node.overview.OverviewTab
import com.altnautica.gcs.feature.node.visor.VisorCaptureTab
import com.altnautica.gcs.feature.node.visor.VisorOverviewCards
import com.altnautica.gcs.feature.node.visor.VisorSessionsTab

/**
 * Vehicle detail: header (profile, liveness, reach, vitals, and Fly, Setup and
 * Parameters actions) and the tab set for the node's profile, plus any extension
 * `node.detail.tab` pages.
 */
@Composable
fun NodeDetailRoute(
    nodeId: DeviceId,
    onBack: () -> Unit,
    onFly: (DeviceId) -> Unit,
    onOpenSetup: (DeviceId) -> Unit,
    onOpenParams: (DeviceId) -> Unit,
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
        onFly = onFly,
        onOpenSetup = onOpenSetup,
        onOpenParams = onOpenParams,
        onOpenNode = onOpenNode,
        onPair = onPair,
        onUnpair = vm::unpair,
        onForget = vm::forgetLocally,
        onClearForget = vm::clearForget,
        onRetryForget = vm::retryForget,
        modifier = modifier,
    )
}

@Composable
fun NodeDetailScreen(
    nodeId: DeviceId,
    state: NodeDetailState,
    forget: ForgetState,
    onBack: () -> Unit,
    onFly: (DeviceId) -> Unit,
    onOpenSetup: (DeviceId) -> Unit,
    onOpenParams: (DeviceId) -> Unit,
    onOpenNode: (DeviceId) -> Unit,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onForget: () -> Unit,
    onClearForget: () -> Unit,
    onRetryForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? NodeDetailState.Ready
    val paired = ready != null && ready.node.reaches.singleOrNull() != Reach.Blocked(BlockReason.NotPaired)
    val items = if (ready != null && paired) rememberTabItems(ready.node, ready.tabs) else emptyList()
    var selectedKey by rememberSaveable(nodeId.value) { mutableStateOf(NodeTab.Overview.name) }
    // A tab that disappears (an extension unloads, the profile changes) resets the selection with it.
    LaunchedEffect(items.map { it.key }) {
        if (items.isNotEmpty() && items.none { it.key == selectedKey }) selectedKey = items.first().key
    }
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val menu = rememberAdosMenuState()
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    ConsoleScaffold(
        title = ready?.node?.name ?: nodeId.value,
        onBack = onBack,
        modifier = modifier,
        tabs = items.map { tabTitle(it) },
        selectedTab = selectedIndex,
        onSelectTab = { index -> items.getOrNull(index)?.let { selectedKey = it.key } },
        actions = {
            if (ready != null && ready.node.canFly()) {
                IconButton(onClick = { onFly(ready.node.id) }) {
                    Icon(Icons.Outlined.SportsEsports, contentDescription = stringResource(R.string.node_fly))
                }
            }
            if (ready != null && NodeTabs.hasFlightController(ready.node)) {
                IconButton(onClick = { onOpenSetup(ready.node.id) }) {
                    Icon(Icons.Outlined.Build, contentDescription = stringResource(R.string.node_setup))
                }
                IconButton(onClick = { onOpenParams(ready.node.id) }) {
                    Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.node_parameters))
                }
            }
            if (ready != null && NodeTabs.hasAgentLink(ready.node)) {
                Box(Modifier.adosMenuAnchor(menu)) {
                    IconButton(onClick = { menu.expanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.node_more))
                    }
                    AdosMenu(
                        state = menu,
                        items = listOf(
                            AdosMenuItem(
                                label = stringResource(R.string.node_unpair),
                                icon = Icons.Outlined.LinkOff,
                                onClick = { confirmUnpair = true },
                            ),
                        ),
                    )
                }
            }
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
                is NodeDetailState.Ready -> if (!paired) {
                    EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.node_unpaired_title),
                        message = stringResource(R.string.node_unpaired_message),
                        action = EmptyStateAction(stringResource(R.string.node_pair), onPair),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Header(state.node, state.vitals)
                        TabContent(
                            node = state.node,
                            selected = items.getOrNull(selectedIndex),
                            onOpenNode = onOpenNode,
                            onPairDrone = { selectedKey = NodeTab.CarriedDrones.name },
                        )
                    }
                }
            }
        }
    }
    if (confirmUnpair) {
        AdosDialog(
            onDismiss = { confirmUnpair = false },
            title = stringResource(R.string.node_unpair_title),
            text = { Text(stringResource(R.string.node_unpair_message)) },
            confirm = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text(stringResource(R.string.node_unpair)) }
            },
            dismiss = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
    if (forget is ForgetState.Failed) {
        AdosDialog(
            onDismiss = onClearForget,
            title = stringResource(R.string.node_unpair_failed_title),
            text = { Text(stringResource(R.string.node_unpair_failed_message)) },
            confirm = { TextButton(onClick = onForget) { Text(stringResource(R.string.node_forget)) } },
            dismiss = { TextButton(onClick = onClearForget) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
    if (forget == ForgetState.CredentialStoreUnavailable) {
        AdosDialog(
            onDismiss = onClearForget,
            title = stringResource(R.string.node_credential_store_title),
            text = { Text(stringResource(R.string.node_credential_store_message)) },
            confirm = { TextButton(onClick = onRetryForget) { Text(stringResource(R.string.node_retry)) } },
            dismiss = { TextButton(onClick = onClearForget) { Text(stringResource(R.string.node_cancel)) } },
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
private fun rememberTabItems(node: NodeSummary, tabs: List<NodeTab>): List<TabItem> {
    val extensionPages by rememberExtensionNodePages(node.id, ExtensionSlots.NODE_DETAIL_TAB)
    return tabs.map { TabItem.Builtin(it) } +
        if (NodeTabs.hasAgentLink(node)) extensionPages.map { TabItem.Extension(it) } else emptyList()
}

@Composable
private fun tabTitle(item: TabItem): String = when (item) {
    is TabItem.Builtin -> stringResource(tabLabel(item.tab))
    is TabItem.Extension -> item.page.contribution.title ?: item.page.pluginName
}

@Composable
private fun TabContent(
    node: NodeSummary,
    selected: TabItem?,
    onOpenNode: (DeviceId) -> Unit,
    onPairDrone: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when (selected) {
            null -> Unit
            is TabItem.Extension -> ExtensionNodePage(node = node.id, page = selected.page, modifier = Modifier.fillMaxSize())
            is TabItem.Builtin -> when (val tab = selected.tab) {
                NodeTab.Overview -> if (node.profile == NodeProfile.GroundStation && node.carriedDroneId == null) {
                    EmptyState(
                        icon = Icons.Outlined.FlightTakeoff,
                        title = stringResource(R.string.node_no_carried_title),
                        message = stringResource(R.string.node_no_carried_message),
                        action = EmptyStateAction(stringResource(R.string.node_pair_drone), onPairDrone),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    OverviewTab(
                        node.id.value,
                        extra = if (node.profile == NodeProfile.Visor) {
                            { VisorOverviewCards(node.id.value) }
                        } else {
                            null
                        },
                    )
                }
                NodeTab.Capture -> VisorCaptureTab(node.id.value)
                NodeTab.Sessions -> VisorSessionsTab(node.id.value)
                NodeTab.CarriedDrones, NodeTab.Radio, NodeTab.Network, NodeTab.Gnss, NodeTab.Lora, NodeTab.Mesh ->
                    GroundStationTab(node.id.value, tab, onOpenNode)
                NodeTab.Logs -> NodeLogsTab(node.id.value)
                NodeTab.Agent -> AgentTab(node.id.value)
            }
        }
    }
}

@Composable
private fun Header(node: NodeSummary, vitals: NodeVitals) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(StatusLevel.Neutral, stringResource(profileLabel(node.profile)))
        LivenessChip(node.liveness)
        node.reaches.forEach { ReachBadge(it) }
        vitals.batteryPct?.let { StatusChip(StatusLevel.Neutral, stringResource(R.string.node_battery, it)) }
        vitals.modeName?.let { StatusChip(StatusLevel.Neutral, it) }
    }
}

private fun tabLabel(tab: NodeTab): Int = when (tab) {
    NodeTab.Overview -> R.string.tab_overview
    NodeTab.Capture -> R.string.tab_capture
    NodeTab.Sessions -> R.string.tab_sessions
    NodeTab.CarriedDrones -> R.string.tab_carried_drones
    NodeTab.Radio -> R.string.tab_radio
    NodeTab.Network -> R.string.tab_network
    NodeTab.Gnss -> R.string.tab_gnss
    NodeTab.Lora -> R.string.tab_lora
    NodeTab.Mesh -> R.string.tab_mesh
    NodeTab.Logs -> R.string.tab_logs
    NodeTab.Agent -> R.string.tab_agent
}
