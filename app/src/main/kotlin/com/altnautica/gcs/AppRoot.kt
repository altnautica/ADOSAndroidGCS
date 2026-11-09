package com.altnautica.gcs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.console.ImmersiveSystemBars
import com.altnautica.gcs.core.designsystem.component.console.LeaveGuard
import com.altnautica.gcs.core.designsystem.component.console.LocalLeaveGuard
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.component.overlay.AdosDialog
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.map.offline.OfflineMapsScreen
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.CockpitRoute
import com.altnautica.gcs.feature.configure.params.ParametersSurfaceRoute
import com.altnautica.gcs.feature.configure.setup.SetupSurfaceRoute
import com.altnautica.gcs.feature.configure.wizard.AircraftSetupRoute
import com.altnautica.gcs.feature.connect.ConnectRoute
import com.altnautica.gcs.feature.home.HomeRoute
import com.altnautica.gcs.feature.home.HomeSurface
import com.altnautica.gcs.feature.home.quick.QuickDestination
import com.altnautica.gcs.feature.home.quick.QuickMenu
import com.altnautica.gcs.feature.logs.dataflash.DataflashRoute
import com.altnautica.gcs.feature.logs.detail.FlightDetailRoute
import com.altnautica.gcs.feature.logs.history.LogsRoute
import com.altnautica.gcs.feature.node.NodeDetailRoute
import com.altnautica.gcs.feature.plan.PlanRoute
import com.altnautica.gcs.feature.settings.SettingsRoute
import com.altnautica.gcs.feature.settings.controller.ControllerRoute
import com.altnautica.gcs.feature.settings.licences.LicencesRoute
import com.altnautica.gcs.navigation.AircraftSetupKey
import com.altnautica.gcs.navigation.BackStackOps
import com.altnautica.gcs.navigation.CloudKey
import com.altnautica.gcs.navigation.CockpitKey
import com.altnautica.gcs.navigation.ConnectKey
import com.altnautica.gcs.navigation.ControllerKey
import com.altnautica.gcs.navigation.FlightKey
import com.altnautica.gcs.navigation.DataflashKey
import com.altnautica.gcs.navigation.HomeKey
import com.altnautica.gcs.navigation.LicencesKey
import com.altnautica.gcs.navigation.LogsKey
import com.altnautica.gcs.navigation.NavTransitions
import com.altnautica.gcs.navigation.NodeKey
import com.altnautica.gcs.navigation.OfflineMapsKey
import com.altnautica.gcs.navigation.PairLink
import com.altnautica.gcs.navigation.ParamsKey
import com.altnautica.gcs.navigation.PhoneRadioKey
import com.altnautica.gcs.navigation.PlanKey
import com.altnautica.gcs.navigation.SettingsKey
import com.altnautica.gcs.navigation.SetupKey
import kotlinx.coroutines.flow.Flow

/**
 * The app shell: the console Home at the root with every surface (cockpit,
 * planner, setup, parameters, vehicle, logs, settings) pushed above it full
 * screen, and the Quick menu over everything. App-level moves (Quick menu, the
 * Home command) go through the [LeaveGuard], so an armed cockpit can confirm first.
 */
@Composable
fun AppRoot(pairLinks: Flow<PairLink>, viewModel: AppViewModel = hiltViewModel()) {
    val backStack = rememberNavBackStack(HomeKey)
    val leaveGuard = remember { LeaveGuard() }
    var quickMenuOpen by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = rememberReducedMotion()
    val transitions = remember(reducedMotion) { NavTransitions(reducedMotion) }
    ImmersiveSystemBars(enabled = isLandscape())
    LaunchedEffect(pairLinks) {
        pairLinks.collect { link -> BackStackOps.push(backStack, ConnectKey(link.code, link.host)) }
    }
    NotificationPermissionPrompt(viewModel)

    // Cockpits leaving the stack without their own exit (Home, pop-to) still close a replay.
    fun closeCockpitsAbove(index: Int) {
        backStack.drop(index + 1).filterIsInstance<CockpitKey>().forEach { viewModel.cockpitClosed(it.id) }
    }

    fun goHome() {
        closeCockpitsAbove(0)
        BackStackOps.goHome(backStack)
    }

    ConsoleCommandHandler { command ->
        when (command) {
            ConsoleCommand.QuickMenu -> {
                quickMenuOpen = !quickMenuOpen
                true
            }
            ConsoleCommand.Home -> {
                quickMenuOpen = false
                leaveGuard.navigate(::goHome)
                true
            }
            else -> false
        }
    }

    CompositionLocalProvider(LocalLeaveGuard provides leaveGuard) {
        NavDisplay(
            backStack = backStack,
            onBack = { BackStackOps.pop(backStack) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = transitions.transitionSpec,
            popTransitionSpec = transitions.popTransitionSpec,
            predictivePopTransitionSpec = transitions.predictivePopTransitionSpec,
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeRoute(
                        onOpenSurface = { surface, id ->
                            when {
                                surface == HomeSurface.Fly -> id?.let { fly(backStack, viewModel, it) }
                                else -> surfaceKey(surface, id)?.let { BackStackOps.push(backStack, it) }
                            }
                        },
                        onAddVehicle = { BackStackOps.push(backStack, ConnectKey()) },
                        onOpenController = { BackStackOps.push(backStack, ControllerKey) },
                        onOpenQuickMenu = { quickMenuOpen = true },
                    )
                }
                entry<PlanKey> { key ->
                    PlanRoute(
                        nodeId = key.nodeId?.let(::DeviceId),
                        onBack = { BackStackOps.pop(backStack) },
                        onStartMission = { fly(backStack, viewModel, it) },
                    )
                }
                entry<SetupKey> { key ->
                    SetupSurfaceRoute(
                        nodeId = DeviceId(key.id),
                        onBack = { BackStackOps.pop(backStack) },
                        onOpenAircraftSetup = { BackStackOps.push(backStack, AircraftSetupKey(key.id)) },
                    )
                }
                entry<AircraftSetupKey> { key ->
                    AircraftSetupRoute(nodeId = DeviceId(key.id), onBack = { BackStackOps.pop(backStack) }, wizard = key.wizard)
                }
                entry<ParamsKey> { key -> ParametersSurfaceRoute(nodeId = DeviceId(key.id), onBack = { BackStackOps.pop(backStack) }) }
                entry<LogsKey> {
                    LogsRoute(
                        onBack = { BackStackOps.pop(backStack) },
                        onOpenFlight = { BackStackOps.push(backStack, FlightKey(it)) },
                        onOpenDataflash = { BackStackOps.push(backStack, DataflashKey(it.value)) },
                        onReplay = { path -> viewModel.startReplay(path) { id -> BackStackOps.push(backStack, CockpitKey(id.value)) } },
                    )
                }
                entry<DataflashKey> { key -> DataflashRoute(nodeId = DeviceId(key.id), onBack = { BackStackOps.pop(backStack) }) }
                entry<SettingsKey> {
                    SettingsRoute(
                        onBack = { BackStackOps.pop(backStack) },
                        onOpenController = { BackStackOps.push(backStack, ControllerKey) },
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
                        onFly = { fly(backStack, viewModel, it) },
                        onOpenSetup = { BackStackOps.push(backStack, SetupKey(it.value)) },
                        onOpenParams = { BackStackOps.push(backStack, ParamsKey(it.value)) },
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
                        onSetUpAircraft = { id -> BackStackOps.push(backStack, AircraftSetupKey(id.value, GROUND_WIZARD)) },
                    )
                }
                entry<ConnectKey> { key ->
                    ConnectRoute(
                        deepLinkCode = key.code,
                        deepLinkHost = key.host,
                        onBack = { BackStackOps.pop(backStack) },
                        onOpenCloud = { BackStackOps.push(backStack, CloudKey) },
                        onOpenPhoneRadio = { BackStackOps.push(backStack, PhoneRadioKey) },
                        onConnected = { id ->
                            viewModel.connected(id, ::goHome) { needing -> BackStackOps.push(backStack, AircraftSetupKey(needing.value, GROUND_WIZARD)) }
                        },
                    )
                }
                entry<FlightKey> { key ->
                    FlightDetailRoute(
                        sessionId = key.sessionId,
                        onBack = { BackStackOps.pop(backStack) },
                        onReplay = { path -> viewModel.startReplay(path) { id -> BackStackOps.push(backStack, CockpitKey(id.value)) } },
                    )
                }
                entry<ControllerKey> { ControllerRoute(onBack = { BackStackOps.pop(backStack) }) }
                entry<OfflineMapsKey> { OfflineMapsScreen(onBack = { BackStackOps.pop(backStack) }) }
                entry<LicencesKey> { LicencesRoute(onBack = { BackStackOps.pop(backStack) }) }
                entry<CloudKey> { CloudRoute(onBack = { BackStackOps.pop(backStack) }) }
                entry<PhoneRadioKey> { PhoneRadioShellRoute(onBack = { BackStackOps.pop(backStack) }) }
            },
        )

        if (quickMenuOpen) {
            QuickMenu(
                onDismiss = { quickMenuOpen = false },
                onNavigate = { destination ->
                    quickMenuOpen = false
                    val target = when (destination) {
                        QuickDestination.Home -> HomeKey
                        QuickDestination.Controller -> ControllerKey
                        is QuickDestination.Surface -> surfaceKey(destination.surface, destination.nodeId)
                    }
                    // Already there: nothing to leave, so nothing to confirm.
                    if (target != null && target != backStack.lastOrNull()) {
                        leaveGuard.navigate {
                            if (destination is QuickDestination.Surface && destination.surface == HomeSurface.Fly) {
                                destination.nodeId?.let(viewModel::reconnect)
                            }
                            val index = backStack.indexOf(target)
                            if (index >= 0) closeCockpitsAbove(index)
                            BackStackOps.navigateTo(backStack, target)
                        }
                    }
                },
            )
        }
    }
}

/** The setup wizard a vehicle that was never set up starts with. */
private const val GROUND_WIZARD = "ground"

/** The route a Home or Quick menu surface opens; null when it needs a vehicle and has none. */
private fun surfaceKey(surface: HomeSurface, nodeId: DeviceId?): NavKey? = when (surface) {
    HomeSurface.Fly -> nodeId?.let { CockpitKey(it.value) }
    HomeSurface.Plan -> PlanKey(nodeId?.value)
    HomeSurface.Setup -> nodeId?.let { SetupKey(it.value) }
    HomeSurface.Parameters -> nodeId?.let { ParamsKey(it.value) }
    HomeSurface.Vehicle -> nodeId?.let { NodeKey(it.value) }
    HomeSurface.Logs -> LogsKey
    HomeSurface.Settings -> SettingsKey
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
    AdosDialog(
        title = stringResource(R.string.permission_notifications_title),
        onDismiss = viewModel::notificationsAsked,
        text = { Text(stringResource(R.string.permission_notifications_message)) },
        confirm = {
            TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.permission_continue))
            }
        },
        dismiss = { TextButton(onClick = viewModel::notificationsAsked) { Text(stringResource(R.string.permission_not_now)) } },
    )
}
