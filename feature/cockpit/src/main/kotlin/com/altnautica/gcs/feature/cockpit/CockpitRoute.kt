package com.altnautica.gcs.feature.cockpit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.EmptyStateAction
import com.altnautica.gcs.core.designsystem.component.console.GlyphSet
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.console.LocalLeaveGuard
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.extensions.ui.ExtensionCockpitEffect
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.feature.cockpit.skillbar.widthClassFor
import com.altnautica.gcs.feature.cockpit.skillbar.reasonRes

/**
 * Full-screen cockpit for [nodeId]; a ground station's cockpit flies its carried
 * drone. Keeps the screen on, honours the max-brightness toggle, takes hardware
 * input while visible, and asks before leaving while the vehicle is armed.
 * [onSetUpAircraft] opens aircraft setup for the flown vehicle (the chip's "Set up aircraft").
 */
@Composable
fun CockpitRoute(nodeId: DeviceId, onExit: () -> Unit, onSetUpAircraft: (DeviceId) -> Unit, modifier: Modifier = Modifier) {
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
    val resources = LocalResources.current

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
    HardwareInput(onAttach = vm::attachInput, onDetach = vm::detachInput)
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
                CockpitEvent.StreamSwitchFailed -> snackbar.showSnackbar(resources.getString(R.string.cockpit_stream_switch_failed))
                CockpitEvent.RecordingFailed -> snackbar.showSnackbar(resources.getString(R.string.cockpit_recording_failed))
                CockpitEvent.ChecklistOverridden -> snackbar.showSnackbar(resources.getString(R.string.cockpit_checklist_overridden))
                is CockpitEvent.TargetRefused ->
                    snackbar.showSnackbar(reasonRes(event.reasonKey)?.let(resources::getString) ?: event.reasonKey)
                is CockpitEvent.Reacquired -> snackbar.showSnackbar(
                    resources.getString(
                        R.string.hud_toast_reacquired,
                        event.trackId,
                        String.format(java.util.Locale.ROOT, "%.2f", event.similarity),
                        String.format(java.util.Locale.ROOT, "%.1f", event.gapS),
                    ),
                )
                CockpitEvent.ReacquireModeNotApplied -> snackbar.showSnackbar(resources.getString(R.string.hud_rail_mode_not_applied))
                CockpitEvent.MinimapLocal -> snackbar.showSnackbar(resources.getString(R.string.hud_map_toast_local))
                CockpitEvent.PilotUnavailable -> snackbar.showSnackbar(resources.getString(R.string.cockpit_pilot_unavailable))
                is CockpitEvent.PilotRefused -> snackbar.showSnackbar(
                    event.reason?.takeIf { it.isNotBlank() }?.let { resources.getString(R.string.cockpit_pilot_refused, it) }
                        ?: resources.getString(R.string.cockpit_pilot_refused_no_reason),
                )
                CockpitEvent.PilotNoAnswer -> snackbar.showSnackbar(resources.getString(R.string.cockpit_pilot_no_answer))
                CockpitEvent.SetUpAircraft -> (vm.extensionNodeId.value ?: nodeId).let(onSetUpAircraft)
            }
        }
    }
    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            val spec = vm.specStrings
            val specKey = SPEC_NOTICE_KEYS[notice.reasonKey]
            val reason = when {
                specKey != null -> spec.format(specKey, notice.args)
                else -> notice.reasonKey?.let { key -> reasonRes(key)?.let(resources::getString) ?: key }
            }
            val detail = notice.message?.let { m -> reasonRes(m)?.let(resources::getString) ?: m }
            val text = listOfNotNull(reason, detail).distinct().joinToString(" · ")
            if (text.isNotEmpty()) {
                snackbar.showSnackbar(text)
            }
        }
    }

    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val width = widthClassFor(with(LocalDensity.current) { windowWidthPx.toDp().value })

    // The cockpit chrome stays clear of the cutout and of system bars swiped in over the cockpit.
    Box(modifier.fillMaxSize().background(AdosTheme.colors.mediaLetterbox).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (status) {
            CockpitStatus.UnknownNode -> EmptyState(
                icon = Icons.Outlined.LinkOff,
                title = stringResource(R.string.cockpit_unknown_node_title),
                message = stringResource(R.string.cockpit_unknown_node_message),
                action = EmptyStateAction(stringResource(R.string.cockpit_exit), onExit),
            )
            CockpitStatus.NoCarriedDrone -> EmptyState(
                icon = Icons.Outlined.FlightTakeoff,
                title = stringResource(R.string.cockpit_no_carried_title),
                message = stringResource(R.string.cockpit_no_carried_message),
                action = EmptyStateAction(stringResource(R.string.cockpit_exit), onExit),
            )
            CockpitStatus.Connecting, CockpitStatus.Ready -> CockpitScreen(
                vm = vm,
                width = width,
                connecting = status == CockpitStatus.Connecting,
                onExit = ::requestExit,
                snackbar = { SnackbarHost(snackbar) },
            )
        }

        pendingLeave?.let { leave ->
            AdosConfirmSheet(
                title = stringResource(R.string.cockpit_leave_armed_title),
                message = stringResource(R.string.cockpit_leave_armed_message),
                onDismiss = { pendingLeave = null },
                danger = true,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    AdosButton(
                        stringResource(R.string.cockpit_stay),
                        onClick = { pendingLeave = null },
                        modifier = Modifier.weight(1f),
                        variant = AdosButtonVariant.Secondary,
                    )
                    AdosButton(
                        stringResource(R.string.cockpit_leave),
                        onClick = {
                            pendingLeave = null
                            leave()
                        },
                        modifier = Modifier.weight(1f),
                        variant = AdosButtonVariant.Danger,
                    )
                }
            }
        }
    }
}

/** Skill notices whose text comes from the cockpit spec strings, filled from the notice args. */
private val SPEC_NOTICE_KEYS = mapOf(
    SkillReason.NO_RESPONSE to "skill.did_not_respond",
    SkillReason.PLUGIN_STOPPED to "skill.plugin_stopped",
)

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
