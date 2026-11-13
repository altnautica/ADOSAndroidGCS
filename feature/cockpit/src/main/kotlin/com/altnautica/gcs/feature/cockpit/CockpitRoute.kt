package com.altnautica.gcs.feature.cockpit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.EmptyStateAction
import com.altnautica.gcs.core.designsystem.component.console.GlyphSet
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.console.LocalLeaveGuard
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayTopBar
import com.altnautica.gcs.core.designsystem.component.overlay.rememberOverlayTopBar
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.core.extensions.ui.ExtensionCockpitEffect
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.skillbar.widthClassFor
import com.altnautica.gcs.feature.cockpit.skillbar.noticeTextOf
import com.altnautica.gcs.feature.cockpit.skillbar.reasonTextOf

/**
 * Full-screen cockpit for [nodeId]; a ground station's cockpit flies its carried
 * drone. Keeps the screen on, honours the max-brightness toggle, takes hardware
 * input while visible, and asks before leaving while the vehicle is armed.
 * [onSetUpAircraft] opens aircraft setup for the flown vehicle (the chip's and the Menu's
 * "Set up aircraft"); [onOpenControls] the controller settings (the Menu's Controls).
 */
@Composable
fun CockpitRoute(
    nodeId: DeviceId,
    onExit: () -> Unit,
    onSetUpAircraft: (DeviceId) -> Unit,
    onOpenControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = hiltViewModel<CockpitViewModel, CockpitViewModel.Factory>(
        key = "cockpit:${nodeId.value}",
        creationCallback = { factory -> factory.create(nodeId.value) },
    )
    val status by vm.status.collectAsStateWithLifecycle()
    val maxBrightness by vm.maxBrightness.collectAsStateWithLifecycle()
    val extensionNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val currentExit by rememberUpdatedState(onExit)
    // The leave waiting on the armed confirm: back out of the cockpit, or an app-level move.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val toastScope = androidx.compose.runtime.rememberCoroutineScope()

    fun guardLeave(proceed: () -> Unit) {
        if (vm.vehicle.value?.telemetry?.heartbeat?.value?.armed == true) pendingLeave = proceed else proceed()
    }

    fun requestExit() = guardLeave { currentExit() }

    val leaveGuard = LocalLeaveGuard.current
    DisposableEffect(leaveGuard) {
        leaveGuard.intercept = { proceed -> guardLeave(proceed) }
        onDispose { leaveGuard.intercept = null }
    }

    KeepScreenOn()
    // The flight screen holds landscape; Home, Settings and Planner keep rotating.
    com.altnautica.gcs.feature.cockpit.chrome.OrientationLock(
        vm.cockpitSpec.layout.orientationLock[com.altnautica.gcs.feature.cockpit.chrome.FLIGHT_SCREEN_LOCK_KEY],
    )
    MaxBrightness(maxBrightness)
    // Started: hardware input and the chip's ETA lease; stopped: both released.
    HardwareInput(
        onAttach = { vm.autonomy.shown = true; vm.attachInput() },
        onDetach = { vm.autonomy.shown = false; vm.detachInput() },
    )
    val palette = AdosTheme.colors
    LaunchedEffect(palette) { vm.setControllerPalette(palette) }
    WindowFocusLoss(vm::onWindowFocusLost)
    TouchpadCapture(LocalControllerUi.current.glyphs == GlyphSet.PlayStation)
    extensionNode?.let { ExtensionCockpitEffect(it) }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = { requestExit() },
    )

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                CockpitEvent.ExitRequested -> requestExit()
                CockpitEvent.StreamSwitchFailed -> snackbar.showSnackbar(vm.specStrings.format("cockpit.toast.stream_switch_failed"))
                CockpitEvent.RecordingFailed -> snackbar.showSnackbar(vm.specStrings.format("cockpit.toast.recording_failed"))
                CockpitEvent.ChecklistOverridden -> snackbar.showSnackbar(vm.specStrings.format("cockpit.toast.checklist_overridden"))
                is CockpitEvent.TargetRefused ->
                    snackbar.showSnackbar(reasonTextOf(vm.specStrings, event.reasonKey))
                is CockpitEvent.Reacquired -> snackbar.showSnackbar(reacquiredText(vm.specStrings, event))
                CockpitEvent.ReacquireModeNotApplied -> snackbar.showSnackbar(vm.specStrings.format("follow.reacquire_not_applied"))
                CockpitEvent.MinimapLocal -> snackbar.showSnackbar(vm.specStrings.format("minimap.toast_local"))
                CockpitEvent.PilotUnavailable -> snackbar.showSnackbar(vm.specStrings.format("cockpit.pilot.unavailable"))
                is CockpitEvent.PilotRefused -> snackbar.showSnackbar(
                    event.reason?.takeIf { it.isNotBlank() }?.let { vm.specStrings.format("cockpit.pilot.refused", mapOf("reason" to it)) }
                        ?: vm.specStrings.format("cockpit.pilot.refused_no_reason"),
                )
                CockpitEvent.PilotNoAnswer -> snackbar.showSnackbar(vm.specStrings.format("cockpit.pilot.no_answer"))
                CockpitEvent.SetUpAircraft -> (vm.extensionNodeId.value ?: nodeId).let(onSetUpAircraft)
                CockpitEvent.OpenControls -> onOpenControls()
            }
        }
    }
    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            val text = noticeTextOf(vm.specStrings, notice)
            if (text.isNotEmpty()) snackbar.showSnackbar(text)
        }
    }

    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val width = widthClassFor(with(LocalDensity.current) { windowWidthPx.toDp().value })

    // The top bar's measured bottom, in window coordinates: every overlay here, the leave sheet too, starts below it.
    val topBar = rememberOverlayTopBar()
    ProvideOverlayTopBar(topBar) {
        // The cockpit chrome stays clear of the cutout and of system bars swiped in over the cockpit.
        Box(modifier.fillMaxSize().background(AdosTheme.colors.mediaLetterbox).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (status) {
                CockpitStatus.UnknownNode -> EmptyState(
                    icon = Icons.Outlined.LinkOff,
                    title = vm.specStrings.format("cockpit.unknown_node.title"),
                    message = vm.specStrings.format("cockpit.unknown_node.message"),
                    action = EmptyStateAction(vm.specStrings.format("cockpit.exit_cockpit"), onExit),
                )
                CockpitStatus.NoCarriedDrone -> EmptyState(
                    icon = Icons.Outlined.FlightTakeoff,
                    title = vm.specStrings.format("cockpit.no_carried.title"),
                    message = vm.specStrings.format("cockpit.no_carried.message"),
                    action = EmptyStateAction(vm.specStrings.format("cockpit.exit_cockpit"), onExit),
                )
                CockpitStatus.Connecting, CockpitStatus.Ready -> CockpitScreen(
                    vm = vm,
                    width = width,
                    connecting = status == CockpitStatus.Connecting,
                    topBar = topBar,
                    onExit = ::requestExit,
                    snackbar = { SnackbarHost(snackbar) },
                    onToast = { text -> toastScope.launch { snackbar.showSnackbar(text) } },
                )
            }

            pendingLeave?.let { leave ->
                CompositionLocalProvider(LocalCockpitStrings provides vm.specStrings) {
                    LeaveArmedSheet(
                        onStay = { pendingLeave = null },
                        onLeave = {
                            pendingLeave = null
                            leave()
                        },
                    )
                }
            }
        }
    }
}

/** Calls [onLost] whenever the cockpit window loses input focus. */
@Composable
private fun WindowFocusLoss(onLost: () -> Unit) {
    val windowInfo = LocalWindowInfo.current
    val lost by rememberUpdatedState(onLost)
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused -> if (!focused) lost() }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun MaxBrightness(on: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, on) {
        val window = activity.window
        val attrs = window.attributes
        attrs.screenBrightness = if (on) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
        onDispose {
            val reset = window.attributes
            reset.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = reset
        }
    }
}

/**
 * Captures the controller touchpad while the cockpit is started and focused, so a stray
 * touchpad click never taps the screen. Captured events are deliberately left unhandled.
 */
@Composable
private fun TouchpadCapture(enabled: Boolean) {
    val view = LocalView.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val capture = enabled && focused && started
    DisposableEffect(view, capture) {
        if (capture) view.requestPointerCapture()
        onDispose { if (capture && view.hasPointerCapture()) view.releasePointerCapture() }
    }
}

/** Hardware input reaches the cockpit only while it is started (visible). */
@Composable
private fun HardwareInput(onAttach: () -> Unit, onDetach: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val attach by rememberUpdatedState(onAttach)
    val detach by rememberUpdatedState(onDetach)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> attach()
                Lifecycle.Event.ON_STOP -> detach()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attach()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            detach()
        }
    }
}
