package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.hud.freshPilot
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.FollowEvent
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.Stamped
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import com.altnautica.gcs.feature.cockpit.band.NavSourceChip
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import com.altnautica.gcs.feature.cockpit.chrome.rememberOnBeat
import com.altnautica.gcs.feature.cockpit.chrome.rememberRedrawBeat
import com.altnautica.gcs.feature.cockpit.hud.DesignatorState
import com.altnautica.gcs.feature.cockpit.hud.FollowRail
import com.altnautica.gcs.feature.cockpit.hud.FollowRangePanel
import com.altnautica.gcs.feature.cockpit.hud.LocalMapInputs
import com.altnautica.gcs.feature.cockpit.hud.LocalPlan
import com.altnautica.gcs.feature.cockpit.hud.LocalMinimap
import com.altnautica.gcs.feature.cockpit.hud.LockChip
import com.altnautica.gcs.feature.cockpit.hud.ProximityRadar
import com.altnautica.gcs.feature.cockpit.hud.RailUi
import com.altnautica.gcs.feature.cockpit.hud.TargetDesignator
import com.altnautica.gcs.feature.cockpit.hud.TargetLayerState
import com.altnautica.gcs.feature.cockpit.hud.TargetSemantics
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerStack
import com.altnautica.gcs.feature.cockpit.hud.rememberTargetDrawer
import com.altnautica.gcs.feature.cockpit.hud.rememberMotionPrefs
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.skillbar.isWide
import kotlinx.coroutines.flow.StateFlow

/** Below this surface width candidate tags are hidden and tracked tags shorten to `#id`. */
private val COMPACT_SURFACE = 600.dp

/** Below this centre-zone height the follow range panel takes its one-line form. */
private val SHORT_CENTRE = 320.dp

/**
 * The data layers of the main video: one canvas for every layer (targets included),
 * the target resolver driver, the TalkBack nodes and designation (tap or long-press
 * drag). [cameraId] is the camera this surface shows. The picture-in-picture video
 * draws no layers.
 */
/** The part of the video the flight chrome covers; the layer chips stay clear of it. */
internal val LocalLayerChipInset = staticCompositionLocalOf { PaddingValues(0.dp) }

@Composable
internal fun VideoLayers(vm: CockpitViewModel, cameraId: String, modifier: Modifier = Modifier) {
    val a = vm.autonomy
    val videoState by vm.videoState.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val actions by vm.targetActions.collectAsStateWithLifecycle()
    val motion = rememberMotionPrefs()
    val layoutClass = LocalLayoutClass.current
    val reducedRate by a.feed.reducedRate.collectAsStateWithLifecycle()
    val layer = remember(cameraId) { TargetLayerState(cameraId) }
    val designator = remember { DesignatorState() }
    // Overlays clear on video loss: no frame for over a second reads as Frozen or not Live.
    val videoLost = videoState !is VideoState.Live
    LaunchedEffect(a.layers, layoutClass) { a.layers.useDefaultLevel(layoutClass.defaultLayerLevel()) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < COMPACT_SURFACE
        val surfaceW = constraints.maxWidth.toFloat()
        val surfaceH = constraints.maxHeight.toFloat()
        val drawer = rememberTargetDrawer(a, motion, compact)
        val chipInset = LocalLayerChipInset.current
        val topClearPx = with(androidx.compose.ui.platform.LocalDensity.current) { chipInset.calculateTopPadding().toPx() }
        SideEffect {
            layer.autonomy = a
            layer.drawer = drawer
            drawer.topClearPx = topClearPx
            layer.videoLost = videoLost
            layer.motion = motion
            layer.vehicleYawDeg = { telemetry?.attitude?.value?.yawRad?.let { Math.toDegrees(it.toDouble()).toFloat() } }
        }
        LayerStack(
            a.layers, cameraId, layer.frameW, layer.frameH, Modifier.fillMaxSize(),
            targets = layer,
            presentation = layoutClass.layerPresentation().copy(reducedRate = reducedRate, chipInset = chipInset),
        )
        TargetSemantics(layer, a, onSelect = { item ->
            item.rect?.let { r ->
                // The menu opens under the box's bottom-centre, as for a tap.
                val (left, top, scale) = layer.fit(surfaceW, surfaceH)
                val anchor = androidx.compose.ui.geometry.Offset(left + r.cx * scale, top + r.bottom * scale)
                designator.open(anchor, mapOf("x" to r.x, "y" to r.y, "width" to r.w, "height" to r.h), item)
            }
        })
        TargetDesignator(layer, designator, actions, onLock = { a.lock(it, cameraId) }, onAction = vm::activateTarget)
    }
}

/** The nav-source chip for the safety band (item 6). */
@Composable
internal fun NavChipSlot(vm: CockpitViewModel, width: WidthClass) {
    val chip by vm.autonomy.navChip.collectAsStateWithLifecycle()
    val inputs by vm.autonomy.navInputsState.collectAsStateWithLifecycle()
    NavSourceChip(chip, inputs, compact = width == WidthClass.Compact, onUseSet = vm.autonomy::useSourceSet)
}

/** A 500 ms clock for age-based chrome (stale panels, coast countdowns), on every fifth beat of the flight screen. */
@Composable
private fun rememberTick(a: CockpitAutonomy): Long {
    val tick = remember { mutableLongStateOf(a.nowMs()) }
    val beat = rememberRedrawBeat()
    LaunchedEffect(a, beat) {
        beat.ticks.collect { n -> if (n % AGE_BEATS == 0L) tick.longValue = a.nowMs() }
    }
    return tick.longValue
}

private const val AGE_BEATS = 500L / RedrawBeat.BEAT_MS

/**
 * The follow state and whether it is fresh, read on the beat: follow frames at up to 20 Hz
 * recompose the reader at most once a beat, and only when the state or its freshness changed.
 */
@Composable
internal fun rememberFollowOnBeat(follow: StateFlow<Stamped<FollowState>?>, nowMs: () -> Long): Pair<FollowState?, Boolean> {
    val sample by rememberOnBeat {
        val f = follow.value
        f?.value to (f != null && nowMs() - f.atMs <= TargetState.FOLLOW_STALE_MS)
    }
    return sample
}

/**
 * Following-mode chrome over the video: the what's-locked chip (top centre), the range
 * panel (bottom centre, above the bottom row) and the right rail. Shown while follow-me
 * reports a phase other than idle, or a target is designated and follow-me is installed.
 */
@Composable
internal fun FollowChrome(vm: CockpitViewModel, width: WidthClass, topInset: Dp, modifier: Modifier = Modifier) {
    val a = vm.autonomy
    val now = rememberTick(a)
    val designation by a.designation.collectAsStateWithLifecycle()
    val installed by a.followInstalled.collectAsStateWithLifecycle()
    // Follow, detections and events arrive at up to 20 Hz: read on the beat, and only what is
    // shown, so this chrome recomposes at most once a beat and only when a shown value changed.
    val (state, fresh) = rememberFollowOnBeat(a.feed.follow, a::nowMs)
    val designatedBatch by rememberOnBeat { designation?.let { a.feed.batches.value[it.cameraId] } }
    val lastReacquired by rememberOnBeat {
        if (designation == null) null else a.feed.events.value.lastOrNull { it is FollowEvent.Reacquired } as FollowEvent.Reacquired?
    }
    val pending by a.reacquirePending.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val skillStates by vm.skillStates.collectAsStateWithLifecycle()
    val active = state != null && fresh && state.phase != FollowPhase.Idle && state.phase != FollowPhase.Lost
    val followingMode = (state != null && state.phase != FollowPhase.Idle) || (designation != null && installed)
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // A short centre (a phone in landscape) keeps even the open panel on one line.
        val short = maxHeight < SHORT_CENTRE
        // The follow strip: directly under the top bar (below the heading scale at Instruments),
        // between the columns. The lock chip and a one-line range panel share it; a tap on the
        // panel opens the full panel right under the strip. Nothing sits at the centre.
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = topInset + AdosSpacing.S2).testTag("follow-strip"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                designation?.let { d ->
                    val batch = designatedBatch
                    val det = batch?.items?.firstOrNull { d.trackId != null && it.trackId == d.trackId }
                    LockChip(
                        trackId = d.trackId,
                        detection = det,
                        feedFresh = batch != null && now - batch.receivedAtMs <= TargetState.DETECTION_STALE_MS,
                        follow = state,
                        followFresh = fresh,
                        lastReacquired = lastReacquired,
                        nowMs = now,
                        onRelease = a::release,
                    )
                }
                if (followingMode && state != null && !expanded) {
                    FollowRangePanel(state, stale = !fresh, width = WidthClass.Compact, modifier = Modifier.clickable { expanded = true })
                }
            }
            if (followingMode && state != null && expanded) {
                val panelWidth = if (width.isWide() && !short) 320.dp else Dp.Unspecified
                FollowRangePanel(
                    state, stale = !fresh, width = if (short) WidthClass.Compact else width,
                    modifier = Modifier
                        .clickable { expanded = false }
                        .let { m -> if (panelWidth != Dp.Unspecified) m.widthIn(min = panelWidth) else m },
                )
            }
        }
        if (!followingMode) return@BoxWithConstraints
        val belly = video.streams.firstOrNull { it.role == CockpitAutonomy.ROLE_DOWN && it.live }
        val concurrent = video.streams.count { it.live && it.switchKind == StreamSwitchKind.Concurrent } >= 2
        val mainId = a.mainCamera(video)
        val swap = if (belly != null && concurrent) {
            val front = com.altnautica.gcs.feature.cockpit.flow.specText("hud.leg.front").uppercase()
            val bellyWord = com.altnautica.gcs.feature.cockpit.flow.specText("hud.leg.belly").uppercase()
            if (mainId == belly.id) bellyWord to front else front to bellyWord
        } else {
            null
        }
        FollowRail(
            ui = RailUi(
                followInstalled = installed,
                followActive = active,
                followEnabled = skillStates[CockpitAutonomy.FOLLOW_SKILL_ID]?.enabled ?: true,
                reacquireMode = state?.reacquireMode,
                pendingMode = pending,
                swapLabels = swap,
                canRecord = canRecord,
                recording = recording,
            ),
            width = width,
            safeHeight = maxHeight - topInset,
            onFollow = { vm.activate(CockpitAutonomy.FOLLOW_SKILL_ID) },
            onReacquireMode = a::setReacquireMode,
            onSwap = {
                // With the PiP shown the legs trade places; without it the other leg takes the main view.
                if (video.pip != null) {
                    vm.swapPip()
                } else {
                    val other = if (mainId == belly?.id) video.streams.firstOrNull { it.live && it.id != belly.id } else belly
                    other?.let { vm.selectStream(it.id) }
                }
            },
            onRecord = vm::toggleRecording,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = AdosSpacing.S2).zIndex(1f),
        )
    }
}

/** The proximity radar with the companion's scan and the vision-nav obstacle rate. */
@Composable
internal fun AutonomyRadar(vm: CockpitViewModel, modifier: Modifier, diameter: Dp) {
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val motion = rememberMotionPrefs()
    ProximityRadar(telemetry, vm.autonomy.feed.scan, vm.autonomy.feed.nav, vm.autonomy::nowMs, modifier, diameter, motion.lowPower)
}

/** Radar diameter per width class (compact / medium / expanded / tv). */
internal fun radarDiameter(width: WidthClass): Dp = when (width) {
    WidthClass.Compact -> 88.dp
    WidthClass.Medium -> 100.dp
    WidthClass.Expanded -> 140.dp
    WidthClass.Tv -> 176.dp
}

/**
 * The minimap in its local frame. Its inputs (the SLAM pose, trail, scan, occupancy and
 * follow lock, and the drone's own local position and attitude) are sampled together on
 * every [MINIMAP_BEATS]th beat of the flight screen, and recompose the map only when one
 * changed, or every 500 ms so ages advance: a pose or attitude stream at the video rate
 * redraws the map at most 5 times a second, in the same frame as the HUD. A tap runs
 * [onClick]; a long-press flies to the point under it, as an offset from the drone
 * ([CockpitViewModel.pickLocalPoint]). The sample is read only by the map's draw pass.
 */
@Composable
internal fun AutonomyLocalMinimap(vm: CockpitViewModel, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val a = vm.autonomy
    val pin by a.minimapPin.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val beat = rememberRedrawBeat()
    val inputs by produceState(localMapInputs(a, telemetry), a, telemetry, beat) {
        beat.ticks.collect { n ->
            if (n % MINIMAP_BEATS != 0L) return@collect
            val next = localMapInputs(a, telemetry)
            if (n % AGE_BEATS == 0L || next.copy(nowMs = value.nowMs) != value) value = next
        }
    }
    Box(modifier) {
        // Read inside the map's draw pass: a new sample redraws the map without recomposing this card.
        LocalMinimap(
            { inputs },
            pin,
            a::setMinimapPin,
            Modifier.fillMaxSize(),
            onTap = onClick,
            onLongPress = vm::pickLocalPoint,
        )
    }
}

/** What the local minimap shows now: the follow lock and the pilot's plan only while fresh. */
private fun localMapInputs(a: CockpitAutonomy, telemetry: VehicleTelemetry?): LocalMapInputs {
    val now = a.nowMs()
    val f = a.feed
    val fresh = f.follow.value?.takeIf { now - it.atMs <= TargetState.FOLLOW_STALE_MS }?.value
    val plan = LocalPlan.of(freshPilot(f.pilot.value, now))
    return LocalMapInputs(f.pose.value, telemetry?.localPosition?.value, telemetry?.attitude?.value, f.trail.value, f.scan.value, f.occupancy.value, fresh, now, plan)
}

/** The local minimap's sampling: every second beat, 5 Hz. */
internal const val MINIMAP_BEATS = 2L
