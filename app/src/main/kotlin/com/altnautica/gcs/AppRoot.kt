package com.altnautica.gcs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.altnautica.gcs.core.map.offline.OfflineMapsScreen
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.CockpitRoute
import com.altnautica.gcs.feature.cockpit.bindings.SkillBindingsScreen
import com.altnautica.gcs.feature.connect.ConnectRoute
import com.altnautica.gcs.feature.fleet.FleetRoute
import com.altnautica.gcs.feature.logs.detail.FlightDetailRoute
import com.altnautica.gcs.feature.logs.history.LogsRoute
import com.altnautica.gcs.feature.node.NodeDetailRoute
import com.altnautica.gcs.feature.plan.PlanRoute
import com.altnautica.gcs.feature.settings.SettingsRoute
import com.altnautica.gcs.feature.settings.licences.LicencesRoute
import com.altnautica.gcs.navigation.BackStackOps
import com.altnautica.gcs.navigation.CloudKey
import com.altnautica.gcs.navigation.CockpitKey
import com.altnautica.gcs.navigation.ConnectKey
import com.altnautica.gcs.navigation.FleetKey
import com.altnautica.gcs.navigation.FlightKey
import com.altnautica.gcs.navigation.LicencesKey
import com.altnautica.gcs.navigation.LogsKey
import com.altnautica.gcs.navigation.NodeKey
import com.altnautica.gcs.navigation.OfflineMapsKey
import com.altnautica.gcs.navigation.PairLink
import com.altnautica.gcs.navigation.PhoneRadioKey
import com.altnautica.gcs.navigation.PlanKey
import com.altnautica.gcs.navigation.SettingsKey
import com.altnautica.gcs.navigation.SkillBindingsKey
import com.altnautica.gcs.navigation.TopLevelKey
import kotlinx.coroutines.flow.Flow

private data class TopLevelItem(val key: TopLevelKey, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TOP_LEVEL = listOf(
    TopLevelItem(FleetKey, R.string.nav_fleet, Icons.Outlined.Hub),
    TopLevelItem(PlanKey, R.string.nav_plan, Icons.Outlined.Map),
    TopLevelItem(LogsKey, R.string.nav_logs, Icons.Outlined.History),
    TopLevelItem(SettingsKey, R.string.nav_settings, Icons.Outlined.Settings),
)

/**
 * The app shell: a navigation suite (bar, rail or drawer by window size) over
 * Fleet, Plan, Logs and Settings; node detail, cockpit and the other routes
 * push above it full screen.
 */
@Composable
fun AppRoot(pairLinks: Flow<PairLink>, viewModel: AppViewModel = hiltViewModel()) {
    val backStack = rememberNavBackStack(FleetKey)
    LaunchedEffect(pairLinks) {
        pairLinks.collect { link -> BackStackOps.push(backStack, ConnectKey(link.code, link.host)) }
    }
    NotificationPermissionPrompt(viewModel)

    val current = BackStackOps.currentTopLevel(backStack)
    val adaptive = currentWindowAdaptiveInfo()
    NavigationSuiteScaffold(
        layoutType = if (BackStackOps.showsSuite(backStack)) {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive)
        } else {
            NavigationSuiteType.None
        },
        containerColor = MaterialTheme.colorScheme.background,
        navigationSuiteItems = {
            TOP_LEVEL.forEach { item ->
                item(
                    selected = item.key == current,
                    onClick = { BackStackOps.selectTopLevel(backStack, item.key) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.label)) },
                )
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { BackStackOps.pop(backStack) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<FleetKey> {
                    FleetRoute(
                        onConnect = { BackStackOps.push(backStack, ConnectKey()) },
                        onOpenNode = { BackStackOps.push(backStack, NodeKey(it.value)) },
                        onFly = { fly(backStack, viewModel, it) },
                    )
                }
                entry<PlanKey> {
                    PlanRoute(nodeId = null, onStartMission = { fly(backStack, viewModel, it) })
                }
                entry<LogsKey> {
                    LogsRoute(onOpenFlight = { BackStackOps.push(backStack, FlightKey(it)) })
                }
                entry<SettingsKey> {
                    SettingsRoute(
                        onOpenSkillBindings = { BackStackOps.push(backStack, SkillBindingsKey) },
                        onOpenOfflineMaps = { BackStackOps.push(backStack, OfflineMapsKey) },
                        onOpenLicences = { BackStackOps.push(backStack, LicencesKey) },
                        onOpenCloud = { BackStackOps.push(backStack, CloudKey) },
                        onOpenPhoneRadio = { BackStackOps.push(backStack, PhoneRadioKey) },
                        versionName = BuildConfig.VERSION_NAME,
                    )
                }
                entry<NodeKey> { key ->
                    NodeDetailRoute(
                        nodeId = DeviceId(key.id),
                        onBack = { BackStackOps.pop(backStack) },
                        onOpenCockpit = { fly(backStack, viewModel, it) },
                        onOpenNode = { BackStackOps.push(backStack, NodeKey(it.value)) },
                        onPair = { BackStackOps.push(backStack, ConnectKey()) },
                    )
                }
                entry<CockpitKey> { key ->
                    CockpitRoute(
                        nodeId = DeviceId(key.id),
                        onExit = {
                            viewModel.cockpitClosed(key.id)
                            BackStackOps.pop(backStack)
                        },
                    )
                }
                entry<ConnectKey> { key ->
                    ConnectRoute(
                        deepLinkCode = key.code,
                        deepLinkHost = key.host,
                        onBack = { BackStackOps.pop(backStack) },
                        onOpenCloud = { BackStackOps.push(backStack, CloudKey) },
                        onOpenPhoneRadio = { BackStackOps.push(backStack, PhoneRadioKey) },
                        onConnected = { BackStackOps.replaceTop(backStack, NodeKey(it.value)) },
                    )
                }
                entry<FlightKey> { key ->
                    FlightDetailRoute(
                        sessionId = key.sessionId,
                        onBack = { BackStackOps.pop(backStack) },
                        onReplay = { path -> viewModel.startReplay(path) { id -> BackStackOps.push(backStack, CockpitKey(id.value)) } },
                    )
                }
                entry<SkillBindingsKey> { SkillBindingsScreen(onBack = { BackStackOps.pop(backStack) }) }
                entry<OfflineMapsKey> { OfflineMapsScreen(onBack = { BackStackOps.pop(backStack) }) }
                entry<LicencesKey> { LicencesRoute(onBack = { BackStackOps.pop(backStack) }) }
                entry<CloudKey> { CloudRoute(onBack = { BackStackOps.pop(backStack) }) }
                entry<PhoneRadioKey> { PhoneRadioShellRoute(onBack = { BackStackOps.pop(backStack) }) }
            },
        )
    }
}

private fun fly(backStack: MutableList<NavKey>, viewModel: AppViewModel, id: DeviceId) {
    viewModel.reconnect(id)
    BackStackOps.push(backStack, CockpitKey(id.value))
}

/** Asks for the notification permission on the first open link, after a rationale (Android 13+). */
@Composable
private fun NotificationPermissionPrompt(viewModel: AppViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val ask by viewModel.askNotifications.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.notificationsAsked() }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!ask) return
    if (granted) {
        LaunchedEffect(Unit) { viewModel.notificationsAsked() }
        return
    }
    AlertDialog(
        onDismissRequest = viewModel::notificationsAsked,
        title = { Text(stringResource(R.string.permission_notifications_title)) },
        text = { Text(stringResource(R.string.permission_notifications_message)) },
        confirmButton = {
            TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.permission_continue))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::notificationsAsked) { Text(stringResource(R.string.permission_not_now)) } },
    )
}
