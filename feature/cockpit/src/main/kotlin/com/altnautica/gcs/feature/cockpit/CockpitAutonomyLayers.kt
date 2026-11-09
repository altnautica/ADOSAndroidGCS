package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.FollowEvent
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import com.altnautica.gcs.feature.cockpit.band.NavSourceChip
import com.altnautica.gcs.feature.cockpit.hud.DesignatorState
import com.altnautica.gcs.feature.cockpit.hud.FollowRail
import com.altnautica.gcs.feature.cockpit.hud.FollowRangePanel
import com.altnautica.gcs.feature.cockpit.hud.LocalMapInputs
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
import kotlinx.coroutines.delay

/** Below this surface width candidate tags are hidden and tracked tags shorten to `#id`. */
private val COMPACT_SURFACE = 600.dp

/**
 * The data layers of the main video: one canvas for every layer (targets included),
 * the target resolver driver, the TalkBack nodes and designation (tap or long-press
 * drag). [cameraId] is the camera this surface shows. The picture-in-picture video
 * draws no layers.
 */
@Composable
internal fun VideoLayers(vm: CockpitViewModel, cameraId: String, modifier: Modifier = Modifier) {
    val a = vm.autonomy
    val videoState by vm.videoState.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val actions by vm.targetActions.collectAsStateWithLifecycle()
    val motion = rememberMotionPrefs()
    val layer = remember(cameraId) { TargetLayerState(cameraId) }
    val designator = remember { DesignatorState() }
    // Overlays clear on video loss: no frame for over a second reads as Frozen or not Live.
    val videoLost = videoState !is VideoState.Live
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < COMPACT_SURFACE
        val surfaceW = constraints.maxWidth.toFloat()
        val surfaceH = constraints.maxHeight.toFloat()
        val drawer = rememberTargetDrawer(a, motion, compact)
        SideEffect {
            layer.autonomy = a
            layer.drawer = drawer
            layer.videoLost = videoLost
            layer.motion = motion
            layer.vehicleYawDeg = { telemetry?.attitude?.value?.yawRad?.let { Math.toDegrees(it.toDouble()).toFloat() } }
        }
        LayerStack(
            a.layers, cameraId, layer.frameW, layer.frameH, Modifier.fillMaxSize(),
            targets = layer,
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

/** A 500 ms clock for age-based chrome (stale panels, coast countdowns). */
@Composable
private fun rememberTick(a: CockpitAutonomy): Long {
    val tick = remember { mutableLongStateOf(a.nowMs()) }
    LaunchedEffect(a) {
        while (true) {
            tick.longValue = a.nowMs()
            delay(500)
        }
    }
    return tick.longValue
}

/**
 * Following-mode chrome over the video: the what's-locked chip (top centre), the range
 * panel (bottom centre, above the Skill Bar) and the right rail. Shown while follow-me
 * reports a phase other than idle, or a target is designated and follow-me is installed.
 */
@Composable
internal fun FollowChrome(vm: CockpitViewModel, width: WidthClass, topInset: Dp, modifier: Modifier = Modifier) {
    val a = vm.autonomy
    val now = rememberTick(a)
    val follow by a.feed.follow.collectAsStateWithLifecycle()
    val designation by a.designation.collectAsStateWithLifecycle()
    val installed by a.followInstalled.collectAsStateWithLifecycle()
    val batches by a.feed.batches.collectAsStateWithLifecycle()
    val events by a.feed.events.collectAsStateWithLifecycle()
    val pending by a.reacquirePending.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val skillStates by vm.skillStates.collectAsStateWithLifecycle()
    val state = follow?.value
    val fresh = follow?.let { now - it.atMs <= TargetState.FOLLOW_STALE_MS } == true
    val active = state != null && fresh && state.phase != FollowPhase.Idle && state.phase != FollowPhase.Lost
    val followingMode = (state != null && state.phase != FollowPhase.Idle) || (designation != null && installed)
    BoxWithConstraints(modifier.fillMaxSize()) {
        designation?.let { d ->
            val batch = batches[d.cameraId]
            val det = batch?.items?.firstOrNull { d.trackId != null && it.trackId == d.trackId }
            LockChip(
                trackId = d.trackId,
                detection = det,
                feedFresh = batch != null && now - batch.receivedAtMs <= TargetState.DETECTION_STALE_MS,
                follow = state,
                followFresh = fresh,
                lastReacquired = events.filterIsInstance<FollowEvent.Reacquired>().lastOrNull(),
                nowMs = now,
                onRelease = a::release,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + AdosSpacing.S2),
            )
        }
        if (!followingMode) return@BoxWithConstraints
        state?.let {
            val panelWidth = if (width.isWide()) 320.dp else Dp.Unspecified
            FollowRangePanel(
                it, stale = !fresh, width = width,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = CockpitLayout.skillBarClearance(width) + 12.dp)
                    .let { m -> if (panelWidth != Dp.Unspecified) m.widthIn(min = panelWidth) else m },
            )
        }
        val belly = video.streams.firstOrNull { it.role == CockpitAutonomy.ROLE_DOWN && it.live }
        val concurrent = video.streams.count { it.live && it.switchKind == StreamSwitchKind.Concurrent } >= 2
        val mainId = a.mainCamera(video)
        val swap = if (belly != null && concurrent) {
            val front = androidx.compose.ui.res.stringResource(R.string.hud_leg_front)
            val bellyWord = androidx.compose.ui.res.stringResource(R.string.hud_leg_belly)
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
                skillEnabled = skillStates.mapValues { it.value.enabled },
            ),
            width = width,
            safeHeight = maxHeight - topInset - CockpitLayout.skillBarClearance(width),
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
            onSkill = { vm.activate(it) },
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

/** The minimap in its local frame, sampled at 2 Hz plus every pose. */
@Composable
internal fun AutonomyLocalMinimap(vm: CockpitViewModel, modifier: Modifier) {
    val a = vm.autonomy
    val now = rememberTick(a)
    val pose by a.feed.pose.collectAsStateWithLifecycle()
    val trail by a.feed.trail.collectAsStateWithLifecycle()
    val scan by a.feed.scan.collectAsStateWithLifecycle()
    val occupancy by a.feed.occupancy.collectAsStateWithLifecycle()
    val follow by a.feed.follow.collectAsStateWithLifecycle()
    val pin by a.minimapPin.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val local by produceState<com.altnautica.gcs.core.model.LocalPosition?>(null, telemetry) { telemetry?.localPosition?.collect { value = it } }
    val attitude by produceState<com.altnautica.gcs.core.model.Attitude?>(null, telemetry) { telemetry?.attitude?.collect { value = it } }
    val fresh = follow?.takeIf { now - it.atMs <= TargetState.FOLLOW_STALE_MS }?.value
    Box(modifier) {
        LocalMinimap(
            LocalMapInputs(pose, local, attitude, trail, scan, occupancy, fresh, a.nowMs()),
            pin,
            a::setMinimapPin,
            Modifier.fillMaxSize(),
        )
    }
}
