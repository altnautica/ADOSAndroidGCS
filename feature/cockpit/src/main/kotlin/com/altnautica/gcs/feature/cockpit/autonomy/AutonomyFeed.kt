package com.altnautica.gcs.feature.cockpit.autonomy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.feature.cockpit.hud.layers.ArFrame
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerFrame
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerSlot
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerSpec
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** A value with its local receive time. */
data class Stamped<T>(val value: T, val atMs: Long)

/** One SLAM trail point; [health] marks degraded segments and lost gaps. */
data class TrailPoint(val north: Float, val east: Float, val health: PoseHealth, val atMs: Long)

/** Decoded occupancy tiles: alpha masks (occupied 90 %, free 10 %, unknown clear) keyed by (ix, iy). */
data class OccupancyLayer(val resM: Float, val cells: Int, val tiles: Map<Pair<Int, Int>, ImageBitmap>, val lastOkMs: Long?)

/**
 * The autonomy feeds of the flown node: detection batches per camera, the navigation
 * stream (pose and trail, scan, nav state, follow state and events) and the live map.
 * An in-process source (the demo drone) is preferred; otherwise the node's agent is
 * read over its extension path, the detection stream shared through [PerceptionFeed].
 */
internal class AutonomyFeed(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val node: StateFlow<DeviceId?>,
    private val repository: ExtensionRepository,
    private val perception: PerceptionFeed,
    private val localApis: Set<LocalNodeApis>,
    /** Overlay frames (`ar`, `obstacles`, `depth`, `surfaces`) go straight to the layer store. */
    private val layers: LayerStore,
    /** Client frames for the nav-stream socket: the view lease. */
    private val outbound: Flow<String>,
    /** The camera the flight-state chip reads the mission ETA from while a mission runs, else null. */
    private val etaCamera: () -> String?,
    /** Where stream frames and detection batches are parsed: off the main thread in the app. */
    private val parseContext: CoroutineContext = EmptyCoroutineContext,
) {
    private val _batches = MutableStateFlow<Map<String, DetectionBatch>>(emptyMap())
    val batches: StateFlow<Map<String, DetectionBatch>> = _batches.asStateFlow()

    private val _follow = MutableStateFlow<Stamped<FollowState>?>(null)
    val follow: StateFlow<Stamped<FollowState>?> = _follow.asStateFlow()

    private val _nav = MutableStateFlow<Stamped<NavState>?>(null)
    val nav: StateFlow<Stamped<NavState>?> = _nav.asStateFlow()

    private val _scan = MutableStateFlow<ScanFrame?>(null)
    val scan: StateFlow<ScanFrame?> = _scan.asStateFlow()

    private val _pose = MutableStateFlow<PoseFrame?>(null)
    val pose: StateFlow<PoseFrame?> = _pose.asStateFlow()

    private val _trail = MutableStateFlow<List<TrailPoint>>(emptyList())
    val trail: StateFlow<List<TrailPoint>> = _trail.asStateFlow()

    private val _events = MutableStateFlow<List<FollowEvent>>(emptyList())
    val events: StateFlow<List<FollowEvent>> = _events.asStateFlow()

    private val _occupancy = MutableStateFlow<OccupancyLayer?>(null)
    val occupancy: StateFlow<OccupancyLayer?> = _occupancy.asStateFlow()

    private val _pilot = MutableStateFlow<PilotState?>(null)

    /** The onboard pilot's state as the navigation stream relays it to every client. */
    val pilot: StateFlow<PilotState?> = _pilot.asStateFlow()

    private val _reducedRate = MutableStateFlow(false)

    /**
     * True while the navigation socket cannot open and the feed polls
     * `GET /api/v1/nav/status?view=` at 2 Hz instead (a radio link that cannot carry it).
     */
    val reducedRate: StateFlow<Boolean> = _reducedRate.asStateFlow()

    /** Local time of the last navigation-socket frame. */
    @Volatile private var lastStreamMs = NEVER

    /** The latest view lease sent to the agent, re-sent as `?view=` by the poll. */
    @Volatile private var lease: String? = null

    /**
     * The `ar` frames of the chip's ETA lease: the next waypoint's ETA on [etaCamera],
     * whichever camera the overlay follows.
     */
    val etaAr = LayerSlot<ArFrame>()

    /** The in-process source for [id], if any. */
    fun local(id: DeviceId): AutonomyFeedApi? = localApis.firstNotNullOfOrNull { it.autonomy(id) }

    fun start() {
        scope.launch {
            node.collectLatest { id ->
                clear()
                if (id == null) return@collectLatest
                coroutineScope {
                    launch { outbound.collect { lease = viewQuery(it) } }
                    launch { detections(id) }
                    launch {
                        retrying {
                            api(id)?.navStream(outbound)
                                ?.map { text -> clock.nowMs().let { now -> now to AutonomyParse.navFrame(text, now) } }
                                ?.flowOn(parseContext)
                                ?.collect { (now, frame) ->
                                    lastStreamMs = now
                                    _reducedRate.value = false
                                    frame?.let { applyNav(it, now) }
                                }
                        }
                    }
                    launch { pollStatus(id) }
                    launch { pollEta(id) }
                    launch { pollMap(id) }
                }
            }
        }
    }

    private fun clear() {
        _batches.value = emptyMap()
        _follow.value = null
        _nav.value = null
        _scan.value = null
        _pose.value = null
        _trail.value = emptyList()
        _events.value = emptyList()
        _occupancy.value = null
        _pilot.value = null
        _reducedRate.value = false
        lastStreamMs = NEVER
        etaAr.clear()
        layers.clear()
        tileCache.clear()
        mapSeq = 0L
    }

    private suspend fun api(id: DeviceId): AutonomyFeedApi? = local(id) ?: repository.api(id)

    /**
     * The radio fallback: while the navigation socket has delivered nothing for
     * [STREAM_QUIET_MS], polls the status route at 2 Hz with this client's view lease and
     * feeds each fresh kind through [onNavText], stamped with its agent-side age so that
     * layer and pilot freshness measure the data, not the poll. A kind whose `ts_ms` was
     * already fed is skipped. [reducedRate] is on only while the poll brings fresh frames
     * the socket did not, so an idle agent never reads as degraded.
     */
    private suspend fun pollStatus(id: DeviceId) {
        val fed = HashMap<String, Long>()
        while (true) {
            delay(STATUS_POLL_MS)
            val now = clock.nowMs()
            if (lastStreamMs != NEVER && now - lastStreamMs < STREAM_QUIET_MS) continue
            val body = api(id)?.let { runCatching { it.navStatus(lease) }.getOrNull() }?.getOrNull()
            if (body == null) {
                _reducedRate.value = false
                continue
            }
            val frames = statusFrames(body)
            for (f in frames) {
                if (fed[f.kind] == f.tsMs) continue
                fed[f.kind] = f.tsMs
                onNavText(f.text, clock.nowMs() - f.ageMs)
            }
            _reducedRate.value = frames.isNotEmpty()
        }
    }

    /**
     * The `?view=` lease the chip's mission ETA needs beyond the socket's own, or null when
     * no mission runs or the socket already leases `ar_route` on [etaCamera].
     */
    internal fun etaView(): String? {
        val camera = etaCamera() ?: return null
        val held = lease
        if (held != null && held.substringBefore(':') == camera && ETA_LAYER in held.substringAfter(':').split(',')) return null
        return "$camera:$ETA_LAYER"
    }

    /**
     * Holds the chip's ETA lease while [etaView] names one. The agent keeps one lease per
     * socket, and that one names the camera the overlay follows (a belly leg, say, with no
     * waypoint in frame), so the ETA camera is leased through the status route every
     * [ETA_POLL_MS] and its `ar` frame kept in [etaAr], stamped with its agent-side age.
     */
    private suspend fun pollEta(id: DeviceId) {
        while (true) {
            delay(ETA_POLL_MS)
            val view = etaView() ?: continue
            val body = api(id)?.let { runCatching { it.navStatus(view) }.getOrNull() }?.getOrNull() ?: continue
            val polled = statusFrames(body).firstOrNull { it.kind == AR_KIND } ?: continue
            val at = clock.nowMs() - polled.ageMs
            val frame = ((AutonomyParse.navFrame(polled.text, at) as? NavFrame.Layer)?.frame as? LayerFrame.Ar)?.frame ?: continue
            etaAr.push(frame, frame.tsMs, at)
        }
    }

    private suspend fun detections(id: DeviceId) {
        val local = local(id)
        if (local != null) {
            retrying {
                local.detections().map { text -> perception.mapBatch(text)?.let { AutonomyParse.batch(it, clock.nowMs()) } }
                    .flowOn(parseContext).collect { b -> b?.let(::putBatch) }
            }
            return
        }
        val release = perception.acquire(id)
        try {
            retrying {
                perception.batches(id)?.map { AutonomyParse.batch(it.payload, clock.nowMs()) }
                    ?.flowOn(parseContext)?.collect { b -> b?.let(::putBatch) } ?: delay(RETRY_MS)
            }
        } finally {
            release()
        }
    }

    private fun putBatch(b: DetectionBatch) {
        _batches.update { it + (b.cameraId to b) }
    }

    /** Parses and applies one navigation frame received (or, for a polled frame, produced) at [receivedAtMs]. */
    internal fun onNavText(text: String, receivedAtMs: Long = clock.nowMs()) {
        AutonomyParse.navFrame(text, receivedAtMs)?.let { applyNav(it, receivedAtMs) }
    }

    /** Applies one parsed navigation frame received at [now], on the collector's thread. */
    private fun applyNav(f: NavFrame, now: Long) {
        when (f) {
            is NavFrame.Pose -> {
                _pose.value = f.pose
                _trail.update { appendTrail(it, f.pose, now) }
            }
            is NavFrame.Scan -> _scan.value = f.scan
            is NavFrame.Nav -> _nav.value = Stamped(f.nav, now)
            is NavFrame.Follow -> _follow.value = Stamped(f.follow, now)
            is NavFrame.Layer -> layers.push(f.frame, now)
            is NavFrame.Event -> _events.update { list -> (list + f.event).filter { now - it.atMs <= EVENT_KEEP_MS } }
            is NavFrame.Pilot -> _pilot.value = f.state
        }
    }

    private var mapSeq = 0L
    private val tileCache = object : LinkedHashMap<Pair<Int, Int>, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, ImageBitmap>?) = size > MAX_TILES
    }

    private suspend fun pollMap(id: DeviceId) {
        while (true) {
            val api = api(id)
            val reply = api?.let { runCatching { it.mapLive(mapSeq) }.getOrNull() }
            val delta = (reply as? AgentResult.Ok)?.value?.let(AutonomyParse::occupancy)
            if (delta != null) {
                val decoded = withContext(Dispatchers.Default) { delta.tiles.mapNotNull { t -> decodeTile(t.png)?.let { (t.ix to t.iy) to it } } }
                decoded.forEach { (k, v) -> tileCache[k] = v }
                mapSeq = delta.seq
                _occupancy.value = OccupancyLayer(delta.resM, delta.tileCells, LinkedHashMap(tileCache), clock.nowMs())
            }
            delay(MAP_POLL_MS)
        }
    }

    private suspend fun retrying(block: suspend () -> Unit) {
        var backoff = RETRY_MS
        while (true) {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // Dropped stream or unreachable node: retry below.
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    companion object {
        const val MAP_POLL_MS = 1_000L
        const val STATUS_POLL_MS = 500L
        /** Above the agent socket's 5 s keepalive, so a healthy but quiet socket never starts the poll. */
        const val STREAM_QUIET_MS = 6_000L
        const val STATUS_FRESH_MS = 2_000L
        const val ETA_POLL_MS = 1_000L
        private const val ETA_LAYER = LayerSpec.AR_ROUTE
        private const val AR_KIND = "ar"
        private const val NEVER = Long.MIN_VALUE

        /** A `{"type":"view","camera_id","layers":[…]}` lease as the status route's `<camera_id>:<layer,…>` query. */
        fun viewQuery(lease: String): String? {
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(lease) }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
            val camera = (o["camera_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            val layers = (o["layers"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            return if (camera.isEmpty() || layers.isEmpty()) null else "$camera:${layers.joinToString(",")}"
        }

        /** One kind of a status body as a stream-shaped `{kind, ts_ms, data}` text, with its agent-side age. */
        data class PolledFrame(val kind: String, val tsMs: Long, val ageMs: Long, val text: String)

        /** Every kind in a status body fresher than [STATUS_FRESH_MS]. */
        fun statusFrames(body: kotlinx.serialization.json.JsonObject): List<PolledFrame> = body.mapNotNull { (kind, entry) ->
            val e = entry as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val age = (e["age_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val data = e["data"] ?: return@mapNotNull null
            if (age > STATUS_FRESH_MS) return@mapNotNull null
            val ts = (e["ts_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val text = kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive(kind))
                put("ts_ms", kotlinx.serialization.json.JsonPrimitive(ts))
                put("data", data)
            }.toString()
            PolledFrame(kind, ts, age.coerceAtLeast(0), text)
        }
        const val MAP_DIM_MS = 5_000L
        const val RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 10_000L
        const val EVENT_KEEP_MS = 5_000L
        const val MAX_TILES = 512
        const val TRAIL_WINDOW_MS = 120_000L
        const val TRAIL_SPACING_M = 0.25f
        const val TRAIL_MAX = 600

        /** Keeps the last 120 s at ≥ 0.25 m spacing, at most 600 points; a health change always records a point. */
        fun appendTrail(trail: List<TrailPoint>, p: PoseFrame, now: Long): List<TrailPoint> {
            val last = trail.lastOrNull()
            val moved = last == null || last.health != p.health ||
                kotlin.math.hypot(p.northM - last.north, p.eastM - last.east) >= TRAIL_SPACING_M
            if (!moved) return trail
            val kept = trail.filter { now - it.atMs <= TRAIL_WINDOW_MS }
            return (kept + TrailPoint(p.northM, p.eastM, p.health, now)).takeLast(TRAIL_MAX)
        }

        /** Grey occupancy PNG → alpha mask: ≤ 100 occupied (90 %), ≥ 230 free (10 %), else clear. */
        fun decodeTile(png: ByteArray): ImageBitmap? {
            val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            src.recycle()
            for (i in px.indices) {
                val v = px[i] and 0xFF
                val a = when {
                    v <= 100 -> 230
                    v >= 230 -> 26
                    else -> 0
                }
                px[i] = (a shl 24) or 0xFFFFFF
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}
