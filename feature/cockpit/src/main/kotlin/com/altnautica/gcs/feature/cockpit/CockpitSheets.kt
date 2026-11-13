package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.overlay.LocalOverlayHost
import com.altnautica.gcs.core.designsystem.component.overlay.LocalOverlayTopInset
import com.altnautica.gcs.core.extensions.ui.ExtensionParametersPanel
import com.altnautica.gcs.core.extensions.ui.ExtensionSlotHost
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.camera.CameraSettingsSheet
import com.altnautica.gcs.feature.cockpit.camera.CameraSheetUi
import com.altnautica.gcs.feature.cockpit.chrome.StatusList
import com.altnautica.gcs.feature.cockpit.chrome.flightSizes
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmHost
import com.altnautica.gcs.feature.cockpit.launch.LaunchSheet
import com.altnautica.gcs.feature.cockpit.modes.ModeSheet
import com.altnautica.gcs.feature.cockpit.panels.QuickSettingsPanel
import com.altnautica.gcs.feature.cockpit.preflight.PreflightSheet
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerSheet
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** The skill's Tweak panel: its manifest parameters, rendered by the extension parameter panel. */
@Composable
private fun SkillTweak(vm: CockpitViewModel, skill: Skill) {
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val node = extNode ?: return
    val src = skill.source as? SkillSource.Extension ?: return
    val params by vm.autonomy.pluginParameters(src.pluginId, skill.params).collectAsStateWithLifecycle(emptyList())
    if (params.isEmpty()) return
    ExtensionParametersPanel(node, src.pluginId, params, Modifier.fillMaxWidth(), sectionKey = "tweak:${skill.id}", nudges = vm.paramNudges)
}

/**
 * Quick settings, the alert list and the sheets, drawn in the cockpit window above the
 * flight surface (z 50). One at a time ([SheetStack]): opening one closes the others, and
 * only the topmost draws. Every sheet starts below the top bar ([LocalOverlayTopInset]).
 */
@Composable
internal fun Overlays(vm: CockpitViewModel, width: WidthClass, status: StatusListState) {
    // The confirm sheet's state is read inside its own layer ([ConfirmLayer]): its hold
    // progress must not recompose every sheet here.
    val settings by vm.settings.collectAsStateWithLifecycle()
    val maxBrightness by vm.maxBrightness.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val stickAllModes by vm.stickAllModes.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    val modeSheet by vm.modeSheet.collectAsStateWithLifecycle()
    val launch by vm.launch.collectAsStateWithLifecycle()
    val tourStep by vm.tourStep.collectAsStateWithLifecycle()
    val statusItems by vm.statusItems.collectAsStateWithLifecycle()
    val wide = width == WidthClass.Expanded || width == WidthClass.Tv
    val sizes = remember(width) { flightSizes(width) }

    val open = openSheets(vm)
    val previous = remember { arrayOf(emptySet<CockpitSheet>()) }
    LaunchedEffect(open) {
        for (sheet in SheetStack.toClose(previous[0], open)) {
            when (sheet) {
                CockpitSheet.Status -> vm.setStatusList(false)
                CockpitSheet.Quick -> vm.setQuickSettings(false)
                CockpitSheet.Picker -> vm.setPicker(false)
                CockpitSheet.Mode -> vm.modeSheetActions.onDismiss()
                CockpitSheet.Launch -> vm.openLaunch(false)
                CockpitSheet.Camera -> vm.setCameraSettings(false)
                CockpitSheet.Preflight, CockpitSheet.Confirm -> Unit
            }
        }
        previous[0] = open
    }
    val top = SheetStack.visible(open)

    if (top == CockpitSheet.Status) {
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
            StatusList(
                statusItems,
                onClose = { vm.setStatusList(false) },
                topInset = sizes.edge + sizes.topBar,
                modifier = Modifier.absoluteOffset(x = with(density) { status.pillX.toDp() }, y = 4.dp),
            )
        }
    }
    Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
        QuickSettingsPanel(
            open = top == CockpitSheet.Quick,
            settings = settings,
            maxBrightness = maxBrightness,
            showTelemetryPaneToggle = wide,
            stickAllModes = stickAllModes,
            onDismiss = { vm.setQuickSettings(false) },
            onAltitudeReference = { vm.setAltitudeReference(it) },
            onDensity = { vm.setDensity(it) },
            onMaxBrightness = vm::setMaxBrightness,
            onTelemetryPane = { vm.setTelemetryPane(it) },
            onStickAllModes = vm::setStickAllModes,
            panels = { extNode?.let { ExtensionSlotHost(it, "cockpit.panel", Modifier.fillMaxWidth()) } },
        )
    }
    when (top) {
        CockpitSheet.Picker -> picker?.let { ui ->
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
                SkillPickerSheet(ui, vm.pickerActions) { skill -> SkillTweak(vm, skill) }
            }
        }
        CockpitSheet.Preflight -> {
            val result by vm.preflight.collectAsStateWithLifecycle()
            val gating by vm.armGateOpen.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
                PreflightSheet(result, gating = gating, onContinue = vm::continuePreflight, onDismiss = { vm.setPreflight(false) })
            }
        }
        CockpitSheet.Mode -> modeSheet?.let { ui ->
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) { ModeSheet(ui, vm.cockpitSpec.flightState.modeSheet, vm.modeSheetActions, vm.cockpitSpec.layout.holdsMs) }
        }
        CockpitSheet.Launch -> launch?.let { ui ->
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
                val launchSpec = vm.cockpitSpec.layout.launch
                LaunchSheet(
                    ui, onDraft = vm::setLaunchDraft, onLaunch = vm::launchNow, onDismiss = { vm.openLaunch(false) },
                    altRange = launchSpec?.let { it.altMinM..it.altMaxM } ?: com.altnautica.gcs.feature.cockpit.launch.LAUNCH_ALT_RANGE,
                )
            }
        }
        CockpitSheet.Confirm -> ConfirmLayer(vm)
        CockpitSheet.Camera -> Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
            val videoState by vm.videoState.collectAsStateWithLifecycle()
            val zoom by vm.digitalZoom.collectAsStateWithLifecycle()
            CameraSettingsSheet(
                CameraSheetUi(
                    streams = video.streams,
                    selectedStreamId = video.selectedStreamId,
                    hasVideo = video.main != null && videoState is VideoState.Live,
                    digitalZoom = zoom,
                    canRecord = canRecord,
                    recording = recording,
                    pipAvailable = video.streams.count { it.live && it.switchKind == StreamSwitchKind.Concurrent } > 1,
                    pipOn = video.pip != null,
                ),
                onDismiss = { vm.setCameraSettings(false) },
                onSelectStream = vm::selectStream,
                onTogglePip = vm::togglePip,
                onToggleRecording = vm::toggleRecording,
            )
        }
        CockpitSheet.Status, CockpitSheet.Quick, null -> Unit
    }
    tourStep?.let { step ->
        // No scrim and no touch handler outside the card: STOP and both columns stay usable.
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS), contentAlignment = Alignment.Center) {
            ControllerTourCard(step, vm::nextTourStep, vm::finishTour)
        }
    }
}

/**
 * The confirm sheet (STOP, Land, RTH and every other confirmed skill), drawn in place at the
 * sheets' level rather than through the window's overlay host: the host adds an overlay after
 * the frame that opened it, so a sheet through it would first show one frame after the tap.
 */
@Composable
private fun ConfirmLayer(vm: CockpitViewModel) {
    val confirm by vm.confirm.state.collectAsStateWithLifecycle()
    // A debuggable build times the tap-to-sheet response from here (the frame this composes in).
    DisposableEffect(Unit) {
        com.altnautica.gcs.feature.cockpit.chrome.FlightFrames.responded()
        onDispose {}
    }
    Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
        CompositionLocalProvider(LocalOverlayHost provides null) { ConfirmHost(confirm, vm.confirm) }
    }
}

/**
 * The sheets open now, before [SheetStack] picks the one that draws. Each sheet's state is
 * read only for whether it is open, so a change inside an open sheet (a hold's progress, a
 * launch step) recomposes that sheet, not this set's readers.
 */
@Composable
internal fun openSheets(vm: CockpitViewModel): Set<CockpitSheet> {
    val statusOpen by vm.statusListOpen.collectAsStateWithLifecycle()
    val quickOpen by vm.quickSettingsOpen.collectAsStateWithLifecycle()
    val cameraOpen by vm.cameraSettingsOpen.collectAsStateWithLifecycle()
    val preflightOpen by vm.preflightOpen.collectAsStateWithLifecycle()
    val confirmOpen by isOpen(vm.confirm.state.collectAsStateWithLifecycle())
    val pickerOpen by isOpen(vm.picker.collectAsStateWithLifecycle())
    val modeOpen by isOpen(vm.modeSheet.collectAsStateWithLifecycle())
    val launchOpen by isOpen(vm.launch.collectAsStateWithLifecycle())
    return buildSet {
        if (statusOpen) add(CockpitSheet.Status)
        if (cameraOpen) add(CockpitSheet.Camera)
        if (quickOpen) add(CockpitSheet.Quick)
        if (pickerOpen) add(CockpitSheet.Picker)
        if (modeOpen) add(CockpitSheet.Mode)
        if (launchOpen) add(CockpitSheet.Launch)
        if (preflightOpen) add(CockpitSheet.Preflight)
        if (confirmOpen) add(CockpitSheet.Confirm)
    }
}

/** Whether [sheet] holds a state: changes only when it opens or closes. */
@Composable
private fun isOpen(sheet: State<Any?>): State<Boolean> = remember(sheet) { derivedStateOf { sheet.value != null } }
