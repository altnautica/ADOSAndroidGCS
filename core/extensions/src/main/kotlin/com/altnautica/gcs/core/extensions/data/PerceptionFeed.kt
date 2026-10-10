package com.altnautica.gcs.core.extensions.data

import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/** One received detection batch, already in the shape extensions receive. */
data class PerceptionBatch(val payload: JsonObject, val receivedAtMs: Long)

/**
 * Detection stream of a node, opened while at least one extension frame holds it.
 * Keeps the latest batch and recent receipt times so `perception.health` reports
 * observed figures only.
 */
@Singleton
class PerceptionFeed @Inject constructor(
    private val repository: ExtensionRepository,
    @ExtensionScope private val scope: CoroutineScope,
) {
    private class Feed(var holders: Int, val job: Job) {
        @Volatile var latest: PerceptionBatch? = null
        val receipts = ArrayDeque<Long>()
        val batches = MutableSharedFlow<PerceptionBatch>(extraBufferCapacity = 4)
    }

    private val feeds = HashMap<DeviceId, Feed>()
    private val json = Json { ignoreUnknownKeys = true }

    var clock: () -> Long = System::currentTimeMillis
        internal set

    fun acquire(node: DeviceId): () -> Unit {
        synchronized(feeds) {
            val f = feeds[node]
            if (f != null) {
                f.holders++
            } else {
                lateinit var feed: Feed
                val job = scope.launch(start = CoroutineStart.LAZY) { run(node, feed) }
                feed = Feed(1, job)
                feeds[node] = feed
                job.start()
            }
        }
        var released = false
        return {
            synchronized(feeds) {
                if (!released) {
                    released = true
                    val f = feeds[node]
                    if (f != null && --f.holders == 0) {
                        feeds.remove(node)
                        f.job.cancel()
                    }
                }
            }
        }
    }

    fun batches(node: DeviceId): SharedFlow<PerceptionBatch>? = synchronized(feeds) { feeds[node]?.batches?.asSharedFlow() }

    fun latest(node: DeviceId): PerceptionBatch? = synchronized(feeds) { feeds[node]?.latest }

    fun receiptTimes(node: DeviceId): List<Long> = synchronized(feeds) { feeds[node]?.receipts?.toList().orEmpty() }

    private suspend fun run(node: DeviceId, feed: Feed) {
        var backoff = 1_000L
        while (true) {
            val api = repository.api(node)
            if (api != null) {
                try {
                    api.detections().collect { text ->
                        backoff = 1_000L
                        val batch = mapBatch(text) ?: return@collect
                        val received = PerceptionBatch(batch, clock())
                        synchronized(feeds) {
                            feed.latest = received
                            feed.receipts.addLast(received.receivedAtMs)
                            while (feed.receipts.size > MAX_RECEIPTS) feed.receipts.removeFirst()
                        }
                        feed.batches.tryEmit(received)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // Dropped stream: reconnect below.
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    /** Wire batch (snake_case) → the camelCase batch extensions receive; null for another version. */
    internal fun mapBatch(text: String): JsonObject? {
        val raw = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val v = (raw["v"] as? JsonPrimitive)?.doubleOrNull
        if (raw.containsKey("v") && v != SUPPORTED_VERSION) return null
        fun num(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val detections = buildJsonArray {
            for (d in (raw["detections"] as? JsonArray).orEmpty()) {
                val o = d as? JsonObject ?: continue
                add(buildJsonObject {
                    (o["bbox"] as? JsonObject)?.let { b ->
                        put("bbox", buildJsonObject {
                            put("x", num(b["x"]) ?: 0.0)
                            put("y", num(b["y"]) ?: 0.0)
                            put("width", num(b["width"]) ?: 0.0)
                            put("height", num(b["height"]) ?: 0.0)
                        })
                    }
                    put("classLabel", str(o["class_label"]))
                    put("confidence", num(o["confidence"]) ?: 0.0)
                    put("trackId", num(o["track_id"])?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("assocConfidence", num(o["assoc_confidence"])?.let { JsonPrimitive(it) } ?: JsonNull)
                    val lock = str(o["lock_state"]).takeIf { it == "locked" || it == "uncertain" || it == "lost" }
                    put("lockState", lock?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("attributes", o["attributes"] as? JsonObject ?: JsonNull)
                })
            }
        }
        return buildJsonObject {
            put("modelId", str(raw["model_id"]))
            put("cameraId", str(raw["camera_id"]))
            put("frameId", num(raw["frame_id"]) ?: 0.0)
            put("tsMs", num(raw["ts_ms"]) ?: 0.0)
            put("frameWidth", num(raw["frame_width"])?.takeIf { it != 0.0 } ?: DEFAULT_FRAME_WIDTH)
            put("frameHeight", num(raw["frame_height"])?.takeIf { it != 0.0 } ?: DEFAULT_FRAME_HEIGHT)
            put("detections", detections)
        }
    }

    companion object {
        const val SUPPORTED_VERSION = 2.0
        const val DEFAULT_FRAME_WIDTH = 640.0
        const val DEFAULT_FRAME_HEIGHT = 480.0
        const val STALE_MS = 2_000L
        const val THROUGHPUT_WINDOW_MS = 3_000L
        private const val MAX_RECEIPTS = 120

        /** Arrival rate over the window; null with fewer than two samples. */
        fun batchesPerSecond(receipts: List<Long>, now: Long, windowMs: Long): Double? {
            if (windowMs <= 0) return null
            val inWindow = receipts.filter { it in (now - windowMs)..now }
            if (inWindow.size < 2) return null
            val span = inWindow.max() - inWindow.min()
            if (span <= 0) return null
            return (inWindow.size - 1).toDouble() / span * 1000.0
        }
    }
}
