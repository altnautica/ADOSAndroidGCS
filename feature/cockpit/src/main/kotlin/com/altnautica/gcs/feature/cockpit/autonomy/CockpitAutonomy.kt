package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.skills.hud.freshPilot
import com.altnautica.gcs.feature.cockpit.band.freshHeartbeatArmed
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyControlApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.extensions.data.ApiResult
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.builtin.EkfSourceSetSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.feature.cockpit.CockpitEvent
import com.altnautica.gcs.feature.cockpit.UI_STOP_MS
import com.altnautica.gcs.feature.cockpit.VideoUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerController
import com.altnautica.gcs.feature.cockpit.hud.layers.VisionEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Minimap frame: the tile map, or the local north/east frame. */
enum class MinimapFrame { Map, Local }

/**
 * Host-side autonomy state for the cockpit: the feeds, the designated target, the
 * re-acquire mode toggle, the nav-source chip and the minimap frame. The resolver and
 * every drawing read from here; nothing here draws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitAutonomy(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val node: StateFlow<DeviceId?>,
    private val telemetry: StateFlow<VehicleTelemetry?>,
    services: StateFlow<VehicleServices?>,
    private val video: StateFlow<VideoUi>,
    skills: StateFlow<List<Skill>>,
    tick: StateFlow<Long>,
    private val repository: ExtensionRepository,
    perception: PerceptionFeed,
    localApis: Set<LocalNodeApis>,
    private val settings: CockpitSettingsRepository,
    private val events: MutableSharedFlow<CockpitEvent>,
    private val activate: (String, Map<String, Any?>) -> Unit,
) {
    /** The video data layers: frames, flags, view and compute leases. */
    val layers: LayerController = LayerController(
        scope, node, settings,
        engine = object : VisionEngine {
            override suspend fun demand(node: DeviceId, body: kotlinx.serialization.json.JsonObject) {
                engineApi(node)?.visionDemand(body)
            }

            override suspend fun status(node: DeviceId) = (engineApi(node)?.visionStatus() as? AgentResult.Ok)?.value

            override suspend fun setDetector(node: DeviceId, modelId: String) = engineApi(node)?.setDetector(modelId) is AgentResult.Ok
        },
        now = clock::nowMs,
        // Only a fresh pilot frame decides armed; an old one falls back to a fresh heartbeat,
        // and an unknown arm state (no fresh source) counts as possibly armed and airborne.
        previewAllowed = {
            val pilot = freshPilot(feed.pilot.value, clock.nowMs())
            if (pilot != null) {
                pilot.armed == false || pilot.owner == com.altnautica.gcs.core.model.PilotOwner.Pilot || pilot.owner == com.altnautica.gcs.core.model.PilotOwner.FcMode
            } else {
                fcArmed() == false
            }
        },
        armed = { (freshPilot(feed.pilot.value, clock.nowMs())?.armed ?: fcArmed()) != false },
    )

    /** The flight controller's arm state from a fresh heartbeat; null when unknown. */
    private fun fcArmed(): Boolean? = freshHeartbeatArmed(telemetry.value?.heartbeat?.value, clock.nowMs())

    val feed: AutonomyFeed = AutonomyFeed(scope, clock, node, repository, perception, localApis, layers.store, layers.outbound)

    /** The node's vision routes: in-process for a simulated node, else its agent. */
    private suspend fun engineApi(id: DeviceId): com.altnautica.gcs.core.agent.nav.AutonomyFeedApi? = feed.local(id) ?: repository.api(id)

    /** The clock every feed timestamp is on. */
    fun nowMs(): Long = clock.nowMs()

    /** Camera of the main video; the agent's primary camera when no tab is selected. */
    fun mainCamera(v: VideoUi = video.value): String = v.selectedStreamId ?: PRIMARY_CAMERA

    /** Camera of the picture-in-picture video, or null without one. */
    fun pipCamera(v: VideoUi = video.value): String? = (v.pip as? VideoSource.AgentWhep)?.streamId

    /** True when [cameraId] is a down-looking leg (no off-frame arrows there). */
    fun isDownCamera(cameraId: String): Boolean = video.value.streams.firstOrNull { it.id == cameraId }?.role == ROLE_DOWN

    private val _designation = MutableStateFlow<Designation?>(null)
    val designation: StateFlow<Designation?> = _designation.asStateFlow()

    /** True when follow-me's skill is installed on the flown node. */
    val followInstalled: StateFlow<Boolean> = skills.map { list -> list.any { it.id == FOLLOW_SKILL_ID } }
        .distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)

    /** The flown node's manifest parameters of [pluginId] named in [keys], in [keys] order: a skill's Tweak panel. */
    fun pluginParameters(pluginId: String, keys: List<String>): kotlinx.coroutines.flow.Flow<List<com.altnautica.gcs.core.extensions.parameters.PluginParameter>> =
        node.flatMapLatest { id ->
            if (id == null || keys.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                repository.extensions(id).map { s ->
                    val ext = (s as? com.altnautica.gcs.core.extensions.data.ExtensionsState.Loaded)?.extensions?.firstOrNull { it.pluginId == pluginId }
                    val byKey = ext?.contributions?.parameters.orEmpty().associateBy { it.key }
                    keys.mapNotNull { byKey[it] }
                }
            }
        }

    // ---- Target memory (LOST rect, coast ghost) ------------------------------------

    private var lastFollowedRect: Box? = null
    private var lostAtMs: Long? = null
    private var lastPhase: FollowPhase? = null
    private val feet = ArrayDeque<Triple<Long, Float, Float>>()
    private var ghost: List<Pair<Float, Float>> = emptyList()

    /** Leg geometry from the agent's camera roster: horizontal FOV and mount pitch, by camera id. */
    private val legs = MutableStateFlow<Map<String, Pair<Float?, Float?>>>(emptyMap())

    private fun loadLegs() {
        scope.launch {
            node.collectLatest { id ->
                legs.value = emptyMap()
                if (id == null || feed.local(id) != null) return@collectLatest
                while (true) {
                    val r = repository.api(id)?.cameras() as? ApiResult.Ok
                    if (r != null) {
                        legs.value = r.value.associate { it.id to (it.fovDeg to it.mountPitchDeg) }
                        return@collectLatest
                    }
                    delay(LEGS_RETRY_MS)
                }
            }
        }
    }

    /** HUD v2 §4 for [cameraId] at [nowMs]; reads the latest feed values. */
    fun resolve(cameraId: String, nowMs: Long, videoLost: Boolean, vehicleYawDeg: Float?): List<DrawItem> {
        val f = feed.follow.value
        val batch = feed.batches.value[cameraId]
        val (hfov, pitch) = legs.value[cameraId] ?: (null to null)
        return TargetState.resolveTargets(
            batch = batch,
            cameraId = cameraId,
            designated = _designation.value,
            follow = f?.value,
            followAgeMs = f?.let { nowMs - it.atMs } ?: Long.MAX_VALUE,
            events = feed.events.value,
            nowMs = nowMs,
            opts = ResolveOptions(
                videoLost = videoLost,
                vehicleYawDeg = vehicleYawDeg,
                cameraIsDown = isDownCamera(cameraId),
                lastFollowedRect = lastFollowedRect,
                lostAtMs = lostAtMs,
                ghost = ghost,
                cameraHfovDeg = hfov,
                cameraVfovDeg = hfov?.let { h -> batch?.let { HudGeometry.verticalFov(h, it.frameWidth, it.frameHeight) } },
                cameraPitchDeg = pitch ?: 0f,
            ),
        )
    }

    private fun observeTargets() {
        scope.launch {
            feed.batches.collect { all ->
                val d = _designation.value ?: return@collect
                val box = all[d.cameraId]?.items?.firstOrNull { it.trackId != null && it.trackId == d.trackId }?.box ?: return@collect
                lastFollowedRect = box
                val now = clock.nowMs()
                feet.addLast(Triple(now, box.cx, box.bottom))
                while (feet.isNotEmpty() && (now - feet.first().first > GHOST_WINDOW_MS || feet.size > GHOST_MAX)) feet.removeFirst()
            }
        }
        scope.launch {
            feed.follow.collect { stamped ->
                val f = stamped?.value ?: return@collect
                val now = clock.nowMs()
                if (f.phase != lastPhase) {
                    if (f.phase == FollowPhase.Coasting) ghost = feet.map { it.second to it.third }
                    if (f.phase == FollowPhase.Lost) {
                        lostAtMs = now
                        _designation.value = null
                    }
                    lastPhase = f.phase
                }
                // Follow-me designates on the agent; the host store mirrors its track.
                val tracking = f.phase in MIRRORED_PHASES && f.trackId != null
                if (tracking && _designation.value?.trackId != f.trackId) {
                    _designation.value = Designation(f.predicted?.cameraId ?: mainCamera(), f.trackId, null)
                }
                pendingMode.value?.let { want -> if (f.reacquireMode == want) settlePending() }
            }
        }
        scope.launch {
            var seen = emptySet<FollowEvent>()
            feed.events.collect { list ->
                for (e in list - seen) when (e) {
                    is FollowEvent.Reacquired -> {
                        _designation.value = Designation(_designation.value?.cameraId ?: mainCamera(), e.trackId, null)
                        events.tryEmit(CockpitEvent.Reacquired(e.trackId, e.similarity, e.gapS))
                    }
                    is FollowEvent.Lost -> {
                        lostAtMs = e.atMs
                        _designation.value = null
                    }
                }
                seen = list.toSet()
            }
        }
    }

    // ---- Designation ----------------------------------------------------------------

    /** Locks [item]'s detection on [cameraId]: `POST /api/vision/designate` with its frame-px box. */
    fun lock(item: DrawItem, cameraId: String) {
        item.detection?.let { lockDetection(it, cameraId) }
    }

    /**
     * The controller's designate (touchpad click): locks the detection under the centre
     * reticle of [cameraId]'s latest batch, else the one nearest it. With nothing detected
     * it says so rather than doing nothing.
     */
    fun designateCentre(cameraId: String) {
        val batch = feed.batches.value[cameraId]
        val d = batch?.let { centreDetection(it) }
        if (d == null) {
            events.tryEmit(CockpitEvent.TargetRefused(REASON_NO_TARGET))
            return
        }
        lockDetection(d, cameraId)
    }

    private fun lockDetection(d: Detection, cameraId: String) {
        scope.launch {
            val body = buildJsonObject {
                put("camera_id", cameraId)
                put("bbox", buildJsonObject {
                    put("x", d.box.x)
                    put("y", d.box.y)
                    put("width", d.box.w)
                    put("height", d.box.h)
                })
                put("class_label", d.classLabel)
                put("confidence", d.confidence)
            }
            if (designate(body)) _designation.value = Designation(cameraId, d.trackId, d.box.takeIf { d.trackId == null })
            else events.tryEmit(CockpitEvent.TargetRefused(REASON_REFUSED))
        }
    }

    /** Releases the host designation (the what's-locked chip `×`). */
    fun release() {
        _designation.value = null
    }

    private suspend fun designate(body: kotlinx.serialization.json.JsonObject): Boolean {
        val id = node.value ?: return false
        val local = feed.local(id)
        if (local != null) {
            val r = (local as? AutonomyControlApi)?.designate(body) as? AgentResult.Ok ?: return false
            return (r.value as? kotlinx.serialization.json.JsonObject)?.get("designated")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
        }
        val r = repository.api(id)?.designate(body) as? ApiResult.Ok ?: return false
        return (r.value as? kotlinx.serialization.json.JsonObject)?.get("designated")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
    }

    // ---- Re-acquire mode --------------------------------------------------------------

    private val pendingMode = MutableStateFlow<String?>(null)

    /** A requested mode not yet echoed by `follow.state.reacquire_mode`; shown dashed. */
    val reacquirePending: StateFlow<String?> = pendingMode.asStateFlow()
    private var pendingJob: Job? = null

    fun setReacquireMode(mode: String) {
        val id = node.value ?: return
        pendingMode.value = mode
        pendingJob?.cancel()
        pendingJob = scope.launch {
            val value = JsonPrimitive(mode)
            val local = feed.local(id)
            val ok = if (local != null) {
                (local as? AutonomyControlApi)?.putPluginConfig(FOLLOW_PLUGIN_ID, REACQUIRE_KEY, value) is AgentResult.Ok
            } else {
                repository.writeConfig(id, FOLLOW_PLUGIN_ID, REACQUIRE_KEY, value) == WriteOutcome.Done
            }
            if (ok) delay(ECHO_TIMEOUT_MS)
            if (pendingMode.value == mode) {
                pendingMode.value = null
                events.tryEmit(CockpitEvent.ReacquireModeNotApplied)
            }
        }
    }

    private fun settlePending() {
        pendingJob?.cancel()
        pendingMode.value = null
    }

    // ---- Nav source -------------------------------------------------------------------

    private val commandedSet = MutableStateFlow<Int?>(null)

    /** EK3_SRC params of the flown vehicle, by name. */
    val ekfParams: StateFlow<Map<String, Int>> = services.flatMapLatest { s ->
        s?.params?.snapshot?.map { snap ->
            snap.values.filterKeys { it.startsWith("EK3_SRC") }.mapValues { it.value.value.toInt() }
        } ?: flowOf(emptyMap())
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), emptyMap())

    private val navInputs: Flow<NavInputs> = combine(
        telemetry.flatMapLatest { t -> t?.let { combine(it.ekf, it.gps) { e, g -> e to g } } ?: flowOf(null to null) },
        feed.nav, commandedSet, ekfParams, tick,
    ) { (ekf, gps), nav, commanded, params, _ ->
        val now = clock.nowMs()
        NavInputs(
            ekfFlags = ekf?.flags,
            ekfAgeMs = ekf?.let { now - it.tMs },
            nav = nav?.value,
            navFresh = nav != null && now - nav.atMs <= TargetState.FOLLOW_STALE_MS,
            commandedSet = commanded,
            params = params,
            gpsFixType = gps?.fix?.let(::fixType),
            sats = gps?.satellites,
            hdop = gps?.hdop,
        )
    }

    val navInputsState: StateFlow<NavInputs?> = navInputs.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    val navChip: StateFlow<NavChip?> = navInputsState.map { it?.let(NavSource::resolve) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    /** Switches EKF source set [set] through the hold-to-confirm skill. */
    fun useSourceSet(set: Int) {
        commandedSet.value = set
        activate(EkfSourceSetSkill.id, mapOf(SkillArgs.EKF_SOURCE_SET to set))
    }

    // ---- Minimap frame ----------------------------------------------------------------

    val minimapPin: StateFlow<String> = node.flatMapLatest { id ->
        id?.let { settings.minimapMode(it.value) } ?: flowOf(CockpitSettingsRepository.MINIMAP_AUTO)
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), CockpitSettingsRepository.MINIMAP_AUTO)

    fun setMinimapPin(mode: String) {
        val id = node.value ?: return
        scope.launch { settings.setMinimapMode(id.value, mode) }
    }

    private var noFixSinceMs: Long? = null

    val minimapFrame: StateFlow<MinimapFrame> = combine(navInputsState, minimapPin) { inputs, pin ->
        val now = clock.nowMs()
        val noFix = (inputs?.gpsFixType ?: 0) < 3
        noFixSinceMs = if (noFix) noFixSinceMs ?: now else null
        val posxy = inputs?.let { it.params[NavSource.param(NavSource.activeSet(it), "POSXY")] }
        when (pin) {
            CockpitSettingsRepository.MINIMAP_LOCAL -> MinimapFrame.Local
            CockpitSettingsRepository.MINIMAP_MAP -> MinimapFrame.Map
            else -> if ((noFixSinceMs?.let { now - it > NO_FIX_LOCAL_MS } == true) || posxy == NavSource.SRC_EXTNAV || posxy == NavSource.SRC_NONE) {
                MinimapFrame.Local
            } else {
                MinimapFrame.Map
            }
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, MinimapFrame.Map)

    init {
        feed.start()
        observeTargets()
        loadLegs()
        scope.launch {
            var previous = MinimapFrame.Map
            minimapFrame.collect { frame ->
                if (frame == MinimapFrame.Local && previous == MinimapFrame.Map && minimapPin.value == CockpitSettingsRepository.MINIMAP_AUTO) {
                    events.tryEmit(CockpitEvent.MinimapLocal)
                }
                previous = frame
            }
        }
    }

    companion object {
        const val PRIMARY_CAMERA = "main"
        const val ROLE_DOWN = "down"
        const val FOLLOW_PLUGIN_ID = "com.altnautica.follow-me"
        const val FOLLOW_SKILL_ID = "$FOLLOW_PLUGIN_ID:follow-me"
        const val REACQUIRE_KEY = "reacquire_mode"
        const val STRICT = "strict"
        const val SMART = "smart"
        const val ECHO_TIMEOUT_MS = 3_000L
        const val NO_FIX_LOCAL_MS = 2_000L
        const val GHOST_WINDOW_MS = 1_000L
        const val GHOST_MAX = 30
        const val LEGS_RETRY_MS = 10_000L
        const val REASON_REFUSED = "commandRejected"

        /** Designate found no detection in view. */
        const val REASON_NO_TARGET = "noTargetInView"

        /** The detection whose box holds the frame centre (the smallest, if several), else the nearest box centre. */
        fun centreDetection(batch: DetectionBatch): Detection? {
            val cx = batch.frameWidth / 2
            val cy = batch.frameHeight / 2
            val under = batch.items.filter { cx in it.box.x..it.box.right && cy in it.box.y..it.box.bottom }
            if (under.isNotEmpty()) return under.minBy { it.box.w * it.box.h }
            return batch.items.minByOrNull { (it.box.cx - cx) * (it.box.cx - cx) + (it.box.cy - cy) * (it.box.cy - cy) }
        }
        private val MIRRORED_PHASES = setOf(FollowPhase.Acquiring, FollowPhase.Following, FollowPhase.Coasting, FollowPhase.Searching, FollowPhase.Holding)

        fun fixType(f: GpsFix): Int = when (f) {
            GpsFix.NoGps -> 0
            GpsFix.NoFix -> 1
            GpsFix.Fix2D -> 2
            GpsFix.Fix3D -> 3
            GpsFix.DGps -> 4
            GpsFix.RtkFloat -> 5
            GpsFix.RtkFixed -> 6
        }
    }
}
