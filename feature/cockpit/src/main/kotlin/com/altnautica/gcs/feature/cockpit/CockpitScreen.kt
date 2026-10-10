package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.extensions.ui.ExtensionMarksLayer
import com.altnautica.gcs.core.extensions.ui.ExtensionSlotHost
import com.altnautica.gcs.core.map.AdosMinimap
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.ui.VideoSurface
import com.altnautica.gcs.core.video.ui.VideoSurfaceRole
import com.altnautica.gcs.feature.cockpit.alerts.AlertStack
import com.altnautica.gcs.feature.cockpit.band.SafetyBand
import com.altnautica.gcs.feature.cockpit.band.SafetyBandHeight
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmHost
import com.altnautica.gcs.feature.cockpit.hud.HudCanvas
import com.altnautica.gcs.feature.cockpit.hud.ProximityRadar
import com.altnautica.gcs.feature.cockpit.hud.TargetDesignator
import com.altnautica.gcs.feature.cockpit.panels.QuickSettingsPanel
import com.altnautica.gcs.feature.cockpit.panels.StreamTabs
import com.altnautica.gcs.feature.cockpit.panels.TelemetryStrip
import com.altnautica.gcs.feature.cockpit.preflight.PreflightSheet
import com.altnautica.gcs.feature.cockpit.settings.CockpitDensity
import com.altnautica.gcs.feature.cockpit.settings.CockpitSettingsRepository
import com.altnautica.gcs.feature.cockpit.skillbar.SkillBar
import com.altnautica.gcs.feature.cockpit.skillbar.SkillDrawer
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** Stacking order inside the cockpit (CS-1). */
private object Z {
    const val VIDEO = 0f
    const val EXT_OVERLAY = 10f
    const val MARKS = 12f
    const val HUD = 15f
    const val ZONES = 20f
    const val PIP = 25f
    const val SKILL_BAR = 30f
    const val BAND = 40f
    const val ALERTS = 45f
    const val SHEETS = 50f
}

private val WIDGET_ZONES = listOf("top-left", "top-right", "bottom-left", "bottom-right")

/**
 * Cockpit layouts: compact landscape (video full-bleed, minimap inset), medium and
 * expanded (draggable video/map split, telemetry pane on expanded), portrait (16:9
 * video on top, band, telemetry and Skill Bar below). Each region collects only the
 * flows it draws, so a telemetry tick never recomposes the whole cockpit.
 */
@Composable
internal fun CockpitScreen(
    vm: CockpitViewModel,
    band: BandState,
    width: WidthClass,
    connecting: Boolean,
    onExit: () -> Unit,
    snackbar: @Composable () -> Unit,
) {
    var preflightOpen by rememberSaveable { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val bandBar: @Composable (Modifier) -> Unit = { m ->
            SafetyBand(
                state = band,
                onExit = onExit,
                onToggleStick = vm::toggleStickControl,
                onOpenPreflight = { preflightOpen = true },
                onOpenQuickSettings = { vm.setQuickSettings(true) },
                onKill = { vm.activate("kill") },
                modifier = m,
            )
        }
        when {
            portrait -> PortraitLayout(vm, width, bandBar)
            width == WidthClass.Compact -> CompactLayout(vm, width, settings.density, bandBar)
            else -> SplitLayout(vm, width, settings.splitRatio, settings.telemetryPane && width == WidthClass.Expanded, bandBar)
        }

        if (connecting) {
            Box(Modifier.align(Alignment.Center).zIndex(Z.ALERTS)) {
                LoadingState(stringResource(R.string.cockpit_connecting))
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp).zIndex(Z.SHEETS)) { snackbar() }
        Overlays(vm, width, preflightOpen, onClosePreflight = { preflightOpen = false })
    }
}

@Composable
private fun CompactLayout(vm: CockpitViewModel, width: WidthClass, density: CockpitDensity, bandBar: @Composable (Modifier) -> Unit) {
    val swapped by vm.minimapSwapped.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        if (swapped) Minimap(vm, Modifier.fillMaxSize().zIndex(Z.VIDEO)) else VideoStage(vm, Modifier.fillMaxSize(), hudTopInset = true)
        Column(Modifier.fillMaxWidth().zIndex(Z.BAND)) {
            bandBar(Modifier)
            Row(Modifier.padding(AdosSpacing.S2), horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                Inset(swapped, vm)
                Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    Tabs(vm)
                    if (density == CockpitDensity.Full) Strip(vm)
                }
            }
        }
        Alerts(vm, Modifier.align(Alignment.TopCenter).padding(top = SafetyBandHeight + AdosSpacing.S2).zIndex(Z.ALERTS))
        WidgetZones(vm, Modifier.fillMaxSize().padding(top = SafetyBandHeight + 140.dp, bottom = 120.dp).zIndex(Z.ZONES))
        Radar(vm, Modifier.align(Alignment.BottomEnd).padding(end = AdosSpacing.S2, bottom = 110.dp).zIndex(Z.ZONES))
        Pip(vm, Modifier.align(Alignment.BottomStart).padding(start = AdosSpacing.S2, bottom = 110.dp).zIndex(Z.PIP))
        Bar(vm, width, Modifier.align(Alignment.BottomCenter).padding(AdosSpacing.S2).zIndex(Z.SKILL_BAR))
    }
}

/** The minimap inset; tapping swaps video and map. */
@Composable
private fun Inset(swapped: Boolean, vm: CockpitViewModel) {
    val m = Modifier.size(width = 200.dp, height = 130.dp).clip(RoundedCornerShape(AdosRadius.Xl)).zIndex(Z.ZONES)
    if (swapped) {
        Box(m) {
            VideoStage(vm, Modifier.fillMaxSize(), hudTopInset = false, showHud = false)
            // Tap target above the video surface; swaps the map back to the main view.
            val swapLabel = stringResource(R.string.cockpit_swap_video_map)
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(Z.SHEETS)
                    .clickable(onClickLabel = swapLabel, onClick = vm::toggleMinimapSwap),
            )
        }
    } else {
        Minimap(vm, m, onClick = vm::toggleMinimapSwap)
    }
}

@Composable
private fun SplitLayout(
    vm: CockpitViewModel,
    width: WidthClass,
    savedRatio: Float,
    telemetryPane: Boolean,
    bandBar: @Composable (Modifier) -> Unit,
) {
    var ratio by remember(savedRatio) { mutableFloatStateOf(savedRatio) }
    val splitLabel = stringResource(R.string.cockpit_split_divider)
    Column(Modifier.fillMaxSize()) {
        bandBar(Modifier.zIndex(Z.BAND))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val total = constraints.maxWidth.toFloat()
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(ratio).fillMaxHeight()) {
                    VideoStage(vm, Modifier.fillMaxSize(), hudTopInset = false)
                    Column(Modifier.padding(AdosSpacing.S2).zIndex(Z.BAND), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) { Tabs(vm) }
                    Alerts(vm, Modifier.align(Alignment.TopCenter).padding(top = AdosSpacing.S12).zIndex(Z.ALERTS))
                    WidgetZones(vm, Modifier.fillMaxSize().padding(top = AdosSpacing.S12, bottom = 120.dp).zIndex(Z.ZONES))
                    Radar(vm, Modifier.align(Alignment.BottomEnd).padding(end = AdosSpacing.S2, bottom = 110.dp).zIndex(Z.ZONES))
                    Pip(vm, Modifier.align(Alignment.BottomStart).padding(start = AdosSpacing.S2, bottom = 110.dp).zIndex(Z.PIP))
                    Bar(vm, width, Modifier.align(Alignment.BottomCenter).padding(AdosSpacing.S2).zIndex(Z.SKILL_BAR))
                }
                // Draggable divider; the ratio is persisted when the drag ends.
                Box(
                    Modifier
                        .width(12.dp)
                        .fillMaxHeight()
                        .background(AdosTheme.colors.bgSurface)
                        .semantics { contentDescription = splitLabel }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                ratio = (ratio + delta / total).coerceIn(CockpitSettingsRepository.MIN_SPLIT, CockpitSettingsRepository.MAX_SPLIT)
                            },
                            onDragStopped = { vm.setSplitRatio(ratio) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 4.dp, height = 40.dp).background(AdosTheme.colors.borderStrong, RoundedCornerShape(AdosRadius.Sm)))
                }
                Box(Modifier.weight(1f - ratio).fillMaxHeight()) {
                    Minimap(vm, Modifier.fillMaxSize())
                }
                if (telemetryPane) {
                    Box(Modifier.padding(AdosSpacing.S2)) { Strip(vm, vertical = true) }
                }
            }
        }
    }
}

@Composable
private fun PortraitLayout(vm: CockpitViewModel, width: WidthClass, bandBar: @Composable (Modifier) -> Unit) {
    Column(Modifier.fillMaxSize().background(AdosTheme.colors.bgCanvas)) {
        bandBar(Modifier.zIndex(Z.BAND))
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            VideoStage(vm, Modifier.fillMaxSize(), hudTopInset = false)
            Pip(vm, Modifier.align(Alignment.BottomStart).padding(AdosSpacing.S2).zIndex(Z.PIP))
        }
        Column(Modifier.padding(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Tabs(vm)
            Alerts(vm, Modifier.fillMaxWidth())
            Strip(vm)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = AdosSpacing.S2)) {
            Minimap(vm, Modifier.fillMaxSize().clip(RoundedCornerShape(AdosRadius.Xl)))
            Radar(vm, Modifier.align(Alignment.BottomEnd).padding(AdosSpacing.S2), diameter = 96.dp)
        }
        Bar(vm, width, Modifier.align(Alignment.CenterHorizontally).padding(AdosSpacing.S2))
    }
}

/** Video, extension overlay, marks, target designation and HUD, stacked per CS-1. */
@Composable
private fun VideoStage(vm: CockpitViewModel, modifier: Modifier, hudTopInset: Boolean, showHud: Boolean = true) {
    val video by vm.video.collectAsStateWithLifecycle()
    val videoState by vm.videoState.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val targetActions by vm.targetActions.collectAsStateWithLifecycle()
    Box(modifier.background(AdosTheme.colors.mediaLetterbox)) {
        VideoSurface(video.main, Modifier.fillMaxSize().zIndex(Z.VIDEO))
        if (videoState !is VideoState.Live) {
            val text = when (videoState) {
                VideoState.Connecting -> stringResource(R.string.cockpit_video_connecting_long)
                is VideoState.Frozen -> stringResource(R.string.cockpit_video_frozen_long)
                is VideoState.Failed -> stringResource(R.string.cockpit_video_failed, (videoState as VideoState.Failed).reason)
                else -> stringResource(R.string.cockpit_video_none_long)
            }
            Text(text, color = AdosTheme.colors.mediaOn, modifier = Modifier.align(Alignment.Center).zIndex(Z.VIDEO + 1))
        }
        extNode?.let { node ->
            ExtensionSlotHost(node, "video.overlay", Modifier.fillMaxSize().zIndex(Z.EXT_OVERLAY))
            ExtensionMarksLayer(node, Modifier.fillMaxSize().zIndex(Z.MARKS))
        }
        TargetDesignator(targetActions, vm::activateTarget, Modifier.fillMaxSize().zIndex(Z.MARKS))
        if (showHud) {
            HudCanvas(
                telemetry = telemetry,
                altitudeReference = settings.altitudeReference,
                density = settings.density,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (hudTopInset) SafetyBandHeight else 0.dp)
                    .zIndex(Z.HUD),
            )
        }
    }
}

@Composable
private fun Minimap(vm: CockpitViewModel, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val state by vm.minimap.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    Box(modifier) {
        AdosMinimap(state.vehicle, state.home, state.trail, Modifier.fillMaxSize(), onClick = onClick)
        extNode?.let { node ->
            val center = state.vehicle?.position
            ExtensionSlotHost(
                node,
                "map.overlay",
                Modifier.fillMaxSize(),
                hostProps = mapOf("center" to center?.let { mapOf("lat" to it.latDeg, "lon" to it.lonDeg) }, "zoom" to MINIMAP_ZOOM),
            )
        }
    }
}

private const val MINIMAP_ZOOM = 16.0

@Composable
private fun Tabs(vm: CockpitViewModel) {
    val video by vm.video.collectAsStateWithLifecycle()
    StreamTabs(video.streams, video.selectedStreamId, video.switchingStreamId, vm::selectStream)
}

@Composable
private fun Strip(vm: CockpitViewModel, vertical: Boolean = false) {
    val strip by vm.strip.collectAsStateWithLifecycle()
    TelemetryStrip(strip, vertical = vertical)
}

@Composable
private fun Alerts(vm: CockpitViewModel, modifier: Modifier) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    AlertStack(alerts, modifier)
}

@Composable
private fun Radar(vm: CockpitViewModel, modifier: Modifier, diameter: androidx.compose.ui.unit.Dp = 120.dp) {
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    ProximityRadar(telemetry, modifier, diameter)
}

@Composable
private fun Pip(vm: CockpitViewModel, modifier: Modifier) {
    val video by vm.video.collectAsStateWithLifecycle()
    val pip = video.pip ?: return
    VideoSurface(
        pip,
        modifier.size(width = 192.dp, height = 108.dp).clip(RoundedCornerShape(AdosRadius.Lg)),
        role = VideoSurfaceRole.Pip,
    )
}

@Composable
private fun Bar(vm: CockpitViewModel, width: WidthClass, modifier: Modifier) {
    val slots by vm.slots.collectAsStateWithLifecycle()
    SkillBar(slots, width, onActivate = { vm.activate(it) }, onOpenDrawer = { vm.setDrawer(true) }, modifier = modifier)
}

@Composable
private fun WidgetZones(vm: CockpitViewModel, modifier: Modifier) {
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val node = extNode ?: return
    Box(modifier.padding(AdosSpacing.S2)) {
        for (zone in WIDGET_ZONES) {
            val align = when (zone) {
                "top-left" -> Alignment.TopStart
                "top-right" -> Alignment.TopEnd
                "bottom-left" -> Alignment.BottomStart
                else -> Alignment.BottomEnd
            }
            ExtensionSlotHost(node, "cockpit.widget", Modifier.align(align).size(width = 240.dp, height = 160.dp), hostProps = mapOf("zone" to zone))
        }
    }
}

@Composable
private fun Overlays(vm: CockpitViewModel, width: WidthClass, preflightOpen: Boolean, onClosePreflight: () -> Unit) {
    val quickOpen by vm.quickSettingsOpen.collectAsStateWithLifecycle()
    val drawerOpen by vm.drawerOpen.collectAsStateWithLifecycle()
    val confirm by vm.confirm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val maxBrightness by vm.maxBrightness.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
        QuickSettingsPanel(
            open = quickOpen,
            settings = settings,
            maxBrightness = maxBrightness,
            showTelemetryPaneToggle = width == WidthClass.Expanded,
            canRecord = canRecord,
            recording = band.recordingStartedAtMs != null,
            pipAvailable = video.streams.count { it.live && it.switchKind == com.altnautica.gcs.core.model.StreamSwitchKind.Concurrent } > 1,
            pipOn = video.pip != null,
            onDismiss = { vm.setQuickSettings(false) },
            onAltitudeReference = { vm.setAltitudeReference(it) },
            onDensity = { vm.setDensity(it) },
            onMaxBrightness = vm::setMaxBrightness,
            onTelemetryPane = { vm.setTelemetryPane(it) },
            onToggleRecording = vm::toggleRecording,
            onTogglePip = vm::togglePip,
            panels = { extNode?.let { ExtensionSlotHost(it, "cockpit.panel", Modifier.fillMaxWidth()) } },
        )
    }
    if (drawerOpen) {
        val skills by vm.drawerSkills.collectAsStateWithLifecycle()
        SkillDrawer(skills, width, onActivate = { vm.activate(it) }, onDismiss = { vm.setDrawer(false) })
    }
    if (preflightOpen) {
        val result by vm.preflight.collectAsStateWithLifecycle()
        PreflightSheet(result, onClosePreflight)
    }
    ConfirmHost(confirm, vm.confirm)
}
