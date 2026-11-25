package com.altnautica.gcs.core.link.param

import android.util.Log
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.TransportMavlinkLink
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.mavlink.messagesOf
import com.altnautica.gcs.core.link.mavlink.withInbox
import com.altnautica.gcs.core.mavlink.enums.MavParamType
import com.altnautica.gcs.core.mavlink.messages.ParamRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestRead
import com.altnautica.gcs.core.mavlink.messages.ParamSet
import com.altnautica.gcs.core.mavlink.messages.ParamValue as ParamValueMsg
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.WriteResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ParamService] over the MAVLink parameter protocol.
 *
 * Values land in a concurrent map as they arrive (from a list download, a
 * read, a write echo or an unsolicited broadcast); [snapshot] republishes at
 * most every 100 ms. A list download waits until every index has arrived or
 * 1.5 s pass without a value, then asks for each missing index with
 * `PARAM_REQUEST_READ` in batches, up to three rounds.
 *
 * With a [cache] and [node], the last known list for the identified flight
 * controller is shown before any download ([fromCache] stays true until a list
 * download confirms it), and every published list is written back.
 *
 * ArduPilot carries integers as C-cast floats; PX4 packs them bytewise into the
 * float, so the codec depends on the firmware family.
 */
class MavlinkParamService(
    private val scope: CoroutineScope,
    private val link: MavlinkLink,
    private val cache: ParamCache? = null,
    private val node: DeviceId? = null,
) : ParamService {

    private val values = ConcurrentHashMap<String, ParamValue>()
    private val byIndex = ConcurrentHashMap<Int, String>()

    @Volatile
    private var count = 0
    private val arrivals = MutableStateFlow(0L)

    @Volatile
    private var dirty = false

    private val _snapshot = MutableStateFlow(ParamSnapshot.EMPTY)
    override val snapshot: StateFlow<ParamSnapshot> = _snapshot.asStateFlow()

    private val _loading = MutableStateFlow(false)
    override val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _fromCache = MutableStateFlow(false)
    override val fromCache: StateFlow<Boolean> = _fromCache.asStateFlow()

    private val listLock = Mutex()
    private val writeLock = Mutex()

    private val seedLock = Mutex()
    private var seeded = false

    @Volatile
    private var cacheKey: ParamCacheKey? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.messagesOf<ParamValueMsg>().collect { onValue(it) } }
        scope.launch {
            while (isActive) {
                if (dirty) publish()
                delay(PUBLISH_PERIOD_MS)
            }
        }
        if (cache != null && node != null) {
            scope.launch { seedOnce(link.target.filterNotNull().first()) }
        }
        // A flight controller heard again after a silence (a reboot) may hold values the list has not seen.
        (link as? TransportMavlinkLink)?.let { l ->
            scope.launch { l.targetSession.drop(1).collect { if (values.isNotEmpty()) _fromCache.value = true } }
        }
    }

    /** Shows the cached list for [t], once per service; live values already received win. */
    private suspend fun seedOnce(t: VehicleTarget) {
        val store = cache ?: return
        val id = node ?: return
        seedLock.withLock {
            if (seeded) return
            seeded = true
            val key = ParamCacheKey.of(id, t)
            val loaded = store.load(key)
            if (loaded != null) {
                val (cachedCount, cached) = loaded
                for (v in cached.values) {
                    values.putIfAbsent(v.name, v)
                    if (v.index in 0 until UINT16_MAX) byIndex.putIfAbsent(v.index, v.name)
                }
                if (count == 0) count = cachedCount
                if (cached.isNotEmpty()) _fromCache.value = true
                publish()
            }
            // Set after the seeding publish so the cached list is not written straight back.
            cacheKey = key
        }
    }

    private fun onValue(msg: ParamValueMsg) {
        val decoded = ParamValue(msg.paramId, decode(msg.paramValue, msg.paramType), msg.paramType, msg.paramIndex)
        if (msg.paramCount in 1 until UINT16_MAX) count = msg.paramCount
        values[decoded.name] = decoded
        if (msg.paramIndex in 0 until UINT16_MAX) byIndex[msg.paramIndex] = decoded.name
        dirty = true
        arrivals.value = arrivals.value + 1
    }

    private fun publish() {
        dirty = false
        val current = HashMap(values)
        val n = count
        _snapshot.value = ParamSnapshot(n, byIndex.size, current)
        val key = cacheKey
        if (n > 0 && key != null) cache?.put(key, n, current)
    }

    private val inflightLock = Any()
    private var inflight: Deferred<ParamLoadResult>? = null

    /**
     * The download runs in the link's scope, so a caller that goes away (a closed screen)
     * does not cancel it; a refresh asked for while one runs joins it, unless it clears.
     */
    override suspend fun refresh(clear: Boolean): ParamLoadResult {
        val job = synchronized(inflightLock) {
            inflight?.takeIf { it.isActive && !clear } ?: scope.async { loadGuarded(clear) }.also { inflight = it }
        }
        return job.await()
    }

    /** A failure here must not reach the link's scope, which would close the link. */
    private suspend fun loadGuarded(clear: Boolean): ParamLoadResult = try {
        load(clear)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "params_failed ${e.message}")
        ParamLoadResult.Failed(e.message ?: REASON_TIMEOUT)
    }

    private suspend fun load(clear: Boolean): ParamLoadResult = listLock.withLock {
        // A link opened a moment ago has not seen the vehicle heartbeat yet; give it a few beats.
        val target = link.target.value
            ?: withTimeoutOrNull(TARGET_WAIT_MS) { link.target.filterNotNull().first() }
            ?: return ParamLoadResult.Failed(REASON_NO_LINK)
        seedOnce(target)
        _loading.value = true
        val startedNs = System.nanoTime()
        try {
            if (clear) {
                values.clear()
                count = 0
                _fromCache.value = false
            }
            // Coverage restarts; known values stay on screen until the download replaces them.
            byIndex.clear()
            publish()
            Log.i(TAG, "params_refresh clear=$clear seeded=${values.size}")
            val result = loadAll(target)
            if (result is ParamLoadResult.Complete) {
                // Drop cached names this flight controller no longer reports.
                values.keys.retainAll(HashSet(byIndex.values))
                _fromCache.value = false
                publish()
            }
            val ms = (System.nanoTime() - startedNs) / 1_000_000
            val name = when (result) {
                is ParamLoadResult.Complete -> "Complete"
                is ParamLoadResult.Incomplete -> "Incomplete"
                is ParamLoadResult.Failed -> "Failed"
            }
            Log.i(TAG, "params_done result=$name count=$count ms=$ms")
            result
        } finally {
            _loading.value = false
        }
    }

    private suspend fun loadAll(target: VehicleTarget): ParamLoadResult {
        val before = arrivals.value
        send(ParamRequestList(target.sysId, target.compId))?.let { return ParamLoadResult.Failed(it) }
        var rounds = 0
        while (true) {
            awaitSilenceOrComplete()
            // Nothing answered the list request (a cached count alone is no answer): ask again.
            if (count == 0 || arrivals.value == before) {
                if (rounds++ >= REPAIR_ROUNDS) return ParamLoadResult.Failed(REASON_TIMEOUT)
                send(ParamRequestList(target.sysId, target.compId))?.let { return ParamLoadResult.Failed(it) }
                continue
            }
            val missing = (0 until count).filterNot { byIndex.containsKey(it) }
            if (missing.isEmpty()) {
                publish()
                return ParamLoadResult.Complete(count)
            }
            if (rounds++ >= REPAIR_ROUNDS) {
                publish()
                return ParamLoadResult.Incomplete(count, missing.size)
            }
            for (chunk in missing.chunked(REPAIR_BATCH)) {
                for (index in chunk) {
                    send(ParamRequestRead(target.sysId, target.compId, "", index))?.let { return ParamLoadResult.Failed(it) }
                }
                withTimeoutOrNull(REPAIR_CHUNK_WAIT_MS) { arrivals.first { chunk.all { byIndex.containsKey(it) } } }
            }
        }
    }

    private fun covered(): Boolean {
        val n = count
        return n > 0 && byIndex.size >= n
    }

    /** Returns once every index has arrived, or no PARAM_VALUE has arrived for [IDLE_MS]. */
    private suspend fun awaitSilenceOrComplete() {
        while (true) {
            val seen = arrivals.value
            if (covered()) return
            withTimeoutOrNull(IDLE_MS) { arrivals.first { it > seen } } ?: return
        }
    }

    override suspend fun set(name: String, value: Double): ParamSetResult = writeLock.withLock {
        val target = link.target.value ?: return ParamSetResult.Failed(REASON_NO_LINK)
        val known = values[name] ?: read(name) ?: return ParamSetResult.UnknownParam
        val wire = encode(value, known.type)
        link.withInbox({ it is ParamValueMsg && it.paramId == name }) { inbox ->
            repeat(SET_RETRIES + 1) {
                send(ParamSet(target.sysId, target.compId, name, wire, known.type))?.let { return@withInbox ParamSetResult.Failed(it) }
                val echo = inbox.receive(SET_TIMEOUT_MS) as? ParamValueMsg
                if (echo != null) {
                    val actual = ParamValue(name, decode(echo.paramValue, echo.paramType), echo.paramType, echo.paramIndex)
                    return@withInbox if (matches(actual.value, value, actual.type)) {
                        ParamSetResult.Confirmed(actual)
                    } else {
                        ParamSetResult.Mismatch(value, actual)
                    }
                }
            }
            ParamSetResult.Timeout
        }
    }

    override suspend fun read(name: String): ParamValue? {
        val target = link.target.value ?: return null
        return link.withInbox({ it is ParamValueMsg && it.paramId == name }) { inbox ->
            repeat(SET_RETRIES + 1) {
                if (send(ParamRequestRead(target.sysId, target.compId, name, -1)) != null) return@withInbox null
                val reply = inbox.receive(SET_TIMEOUT_MS) as? ParamValueMsg
                if (reply != null) return@withInbox ParamValue(name, decode(reply.paramValue, reply.paramType), reply.paramType, reply.paramIndex)
            }
            null
        }
    }

    private suspend fun send(message: com.altnautica.gcs.core.mavlink.messages.MavMessage): String? =
        when (val w = link.send(message)) {
            WriteResult.Sent -> null
            WriteResult.ReceiveOnly -> "receiveOnly"
            WriteResult.NotAuthorized -> "notAuthorized"
            is WriteResult.Failed -> w.message
        }

    private val bytewise: Boolean get() = link.target.value?.family == FirmwareFamily.Px4

    private fun decode(raw: Float, type: Int): Double {
        if (!isInteger(type)) return raw.toDouble()
        if (!bytewise) return raw.toDouble()
        val bits = java.lang.Float.floatToRawIntBits(raw)
        return when (type) {
            MavParamType.MAV_PARAM_TYPE_UINT8 -> (bits and 0xFF).toDouble()
            MavParamType.MAV_PARAM_TYPE_INT8 -> bits.toByte().toDouble()
            MavParamType.MAV_PARAM_TYPE_UINT16 -> (bits and 0xFFFF).toDouble()
            MavParamType.MAV_PARAM_TYPE_INT16 -> bits.toShort().toDouble()
            MavParamType.MAV_PARAM_TYPE_UINT32 -> (bits.toLong() and 0xFFFFFFFFL).toDouble()
            else -> bits.toDouble()
        }
    }

    private fun encode(value: Double, type: Int): Float {
        if (!isInteger(type) || !bytewise) return value.toFloat()
        val bits = when (type) {
            MavParamType.MAV_PARAM_TYPE_UINT32 -> value.toLong().toInt()
            else -> value.toInt()
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun matches(actual: Double, requested: Double, type: Int): Boolean =
        if (isInteger(type)) {
            Math.round(actual) == Math.round(requested)
        } else {
            abs(actual - requested) <= max(1e-6, abs(requested) * 1e-6)
        }

    private fun isInteger(type: Int) = type in INTEGER_TYPES

    companion object {
        const val PUBLISH_PERIOD_MS = 100L
        const val IDLE_MS = 1_500L
        const val REPAIR_ROUNDS = 3
        const val REPAIR_BATCH = 20
        const val REPAIR_CHUNK_WAIT_MS = 600L
        const val SET_TIMEOUT_MS = 1_500L
        const val SET_RETRIES = 2
        const val TARGET_WAIT_MS = 5_000L
        const val REASON_NO_LINK = "noFcLink"
        const val REASON_TIMEOUT = "timeout"
        private const val UINT16_MAX = 0xFFFF
        private const val TAG = "AdosLink"

        private val INTEGER_TYPES = setOf(
            MavParamType.MAV_PARAM_TYPE_UINT8,
            MavParamType.MAV_PARAM_TYPE_INT8,
            MavParamType.MAV_PARAM_TYPE_UINT16,
            MavParamType.MAV_PARAM_TYPE_INT16,
            MavParamType.MAV_PARAM_TYPE_UINT32,
            MavParamType.MAV_PARAM_TYPE_INT32,
        )
    }
}
