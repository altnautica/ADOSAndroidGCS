package com.altnautica.gcs.feature.cockpit.hud.layers

import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** The flown node's vision engine routes the layers use; null when no node answers. */
interface VisionEngine {
    /** `POST /api/vision/demand`. */
    suspend fun demand(node: DeviceId, body: JsonObject)

    /** `GET /api/vision/status`. */
    suspend fun status(node: DeviceId): JsonObject?

    /** `PUT /api/vision/detector`; true when accepted. */
    suspend fun setDetector(node: DeviceId, modelId: String): Boolean
}

/** Depth layer options: cell style and the range cut-off (null draws no depth cells). */
data class DepthOptions(val style: CellStyle = CellStyle.Solid, val rangeM: Float? = 5f)

/**
 * The video data layers of the flown vehicle: the frames ([store]), the operator's level
 * and per-layer flags (persisted per vehicle), the open view's forced layers, and the
 * compute leases that make the agent produce only what this surface shows.
 *
 * Leases are held only while a video surface is attached (lifecycle at least STARTED):
 * every 2 s the nav-stream `{"type":"view"}` lease names the on-screen camera and the
 * enabled agent-computed layers, and `POST /api/vision/demand` holds detection, depth
 * and surfaces on that camera while their layers are on. Detaching releases all of them.
 */
class LayerController(
    private val scope: CoroutineScope,
    private val node: StateFlow<DeviceId?>,
    private val settings: CockpitSettingsRepository,
    val engine: VisionEngine,
    val now: () -> Long,
    /** True when a `loaded` mission previews: disarmed, or the pilot or the FC (not a skill) flies. */
    val previewAllowed: () -> Boolean,
    /** True while the vehicle is armed. */
    val armed: () -> Boolean,
) {
    val store = LayerStore()

    var spec: LayerSpec = LayerSpec.DEFAULT
        private set

    private val base = MutableStateFlow(spec.defaults())
    private val _view = MutableStateFlow(LayerSpec.VIEW_PILOT)
    val view: StateFlow<String> = _view.asStateFlow()

    private val _flags = MutableStateFlow(spec.defaults())

    /** The effective flags: the operator's, plus the open view's forced layers. */
    val flags: StateFlow<LayerFlags> = _flags.asStateFlow()

    private val _depth = MutableStateFlow(DepthOptions())
    val depth: StateFlow<DepthOptions> = _depth.asStateFlow()

    // The latest view lease is replayed to a socket that (re)connects, so a lease sent
    // while the nav stream was reconnecting is not lost until the next renewal.
    private val _outbound = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)

    /** Text frames for the nav-stream socket (the view lease). */
    val outbound: SharedFlow<String> = _outbound.asSharedFlow()

    /** This surface's demand-lease owner: unique per cockpit, so two phones never share a lease. */
    val owner: String = "$OWNER_PREFIX:${Random.nextLong().toULong().toString(16).padStart(16, '0')}"

    private val attached = MutableStateFlow<String?>(null)
    private var attachCount = 0

    /** True once the vehicle's own saved layer state was restored. */
    private var persisted = false
    private var defaultLevel = LayerLevel.Full

    init {
        scope.launch { node.collectLatest { id -> if (id != null) settings.layers(id.value).collect(::restore) } }
        scope.launch { attached.collectLatest { camera -> if (camera != null) leases(camera) } }
    }

    /** Uses the cockpit spec read from the app's assets (the built-in copy until then). */
    fun useSpec(s: LayerSpec) {
        if (s == spec) return
        spec = s
        if (!persisted) base.value = startFlags()
        recompute()
    }

    /**
     * The level a vehicle starts at before the operator picks one: the layout class's
     * `default_layer_level` (route on compact and portrait). A saved state wins.
     */
    fun useDefaultLevel(level: LayerLevel) {
        if (level == defaultLevel) return
        defaultLevel = level
        if (!persisted) {
            base.value = startFlags()
            recompute()
        }
    }

    private fun startFlags(): LayerFlags = spec.defaults().let { if (defaultLevel == it.level) it else it.withLevel(defaultLevel, spec) }

    /** The flown node, for the vision panel's engine reads. */
    val nodeId: DeviceId? get() = node.value

    fun cycleLevel(step: Int) = setLevel(base.value.level.cycle(step))

    fun setLevel(level: LayerLevel) = update(base.value.withLevel(level, spec))

    fun toggle(id: String) = update(base.value.toggled(id))

    fun setView(id: String) {
        _view.value = id
        recompute()
    }

    fun setDepthOptions(o: DepthOptions) {
        _depth.value = o
        persist()
    }

    private fun update(f: LayerFlags) {
        base.value = f
        recompute()
        persist()
    }

    private fun recompute() {
        _flags.value = base.value.forcing(spec.forced(_view.value))
    }

    private fun persist() {
        val id = node.value ?: return
        val json = encode(base.value, _depth.value)
        scope.launch { settings.setLayers(id.value, json) }
    }

    private fun restore(json: String?) {
        val saved = decode(json)
        persisted = saved != null
        val (f, d) = saved ?: (startFlags() to DepthOptions())
        base.value = f
        _depth.value = d
        recompute()
    }

    /**
     * Holds the leases for [cameraId] while a surface shows it. Returns the release; the
     * leases drop with the last release.
     */
    fun attach(cameraId: String): () -> Unit {
        attachCount++
        attached.value = cameraId
        var released = false
        return {
            if (!released) {
                released = true
                if (--attachCount == 0) attached.value = null
            }
        }
    }

    /**
     * Sends the view lease and the demand leases for [camera] at once, again the moment
     * the layer set changes, and every [RENEW_MS] otherwise.
     */
    private suspend fun leases(camera: String) {
        val held = HashSet<String>()
        try {
            while (true) {
                val f = flags.value
                val layers = AGENT_LAYERS.filter { f.isOn(it) }
                _outbound.tryEmit(viewLease(camera, layers))
                val want = buildSet {
                    if (f.isOn(LayerSpec.TARGETS)) add(DETECTION)
                    if (f.isOn(LayerSpec.DEPTH)) add(LayerSpec.DEPTH)
                    if (f.isOn(LayerSpec.SURFACES)) add(LayerSpec.SURFACES)
                }
                node.value?.let { id ->
                    for (k in want) runCatching { engine.demand(id, demandBody(k, camera, HOLD_S, owner)) }
                    for (k in held - want) runCatching { engine.demand(id, demandBody(k, camera, 0.0, owner)) }
                }
                held.clear()
                held.addAll(want)
                withTimeoutOrNull(RENEW_MS) { flags.first { it != f } }
            }
        } finally {
            // The agent keeps one view lease per socket (the latest wins): a camera switch
            // simply names the new camera, and only a full detach sends the empty release.
            if (attached.value == null) _outbound.tryEmit(viewLease(camera, emptyList()))
            val id = node.value
            if (id != null && held.isNotEmpty()) {
                val release = held.toList()
                scope.launch { for (k in release) runCatching { engine.demand(id, demandBody(k, camera, 0.0, owner)) } }
            }
        }
    }

    companion object {
        const val RENEW_MS = 2_000L
        const val HOLD_S = 6.0
        const val OWNER_PREFIX = "android"
        const val DETECTION = "detection"

        /** The layers the agent computes per view lease. */
        val AGENT_LAYERS = listOf(LayerSpec.AR_ROUTE, LayerSpec.AR_MOTION, LayerSpec.OBSTACLES, LayerSpec.DEPTH, LayerSpec.SURFACES)

        fun viewLease(camera: String, layers: List<String>): String = buildJsonObject {
            put("type", "view")
            put("camera_id", camera)
            put("layers", buildJsonArray { layers.forEach { add(JsonPrimitive(it)) } })
        }.toString()

        /** `POST /api/vision/demand` body; detection names no kind. */
        fun demandBody(kind: String, camera: String, holdS: Double, owner: String): JsonObject = buildJsonObject {
            if (kind != DETECTION) put("kind", kind)
            put("camera_id", camera)
            put("hold_s", holdS)
            put("owner", owner)
        }

        fun encode(f: LayerFlags, d: DepthOptions): String = buildJsonObject {
            put("level", f.level.wire)
            put("on", buildJsonArray { f.on.sorted().forEach { add(JsonPrimitive(it)) } })
            put("depth_style", d.style.name.lowercase())
            put("depth_range_m", d.rangeM?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()

        fun decode(json: String?): Pair<LayerFlags, DepthOptions>? = runCatching {
            val o = Json.parseToJsonElement(json ?: return null).jsonObject
            val level = LayerLevel.of(o["level"]?.jsonPrimitive?.contentOrNull) ?: return null
            val on = o["on"]!!.jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
            val style = if (o["depth_style"]?.jsonPrimitive?.contentOrNull == "outline") CellStyle.Outline else CellStyle.Solid
            val range = (o["depth_range_m"] as? JsonPrimitive)?.floatOrNull
            LayerFlags(level, on) to DepthOptions(style, range)
        }.getOrNull()
    }
}
