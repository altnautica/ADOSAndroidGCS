package com.altnautica.gcs.core.link.param

import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.mavlink.messagesOf
import com.altnautica.gcs.core.link.mavlink.withInbox
import com.altnautica.gcs.core.mavlink.enums.MavParamType
import com.altnautica.gcs.core.mavlink.messages.ParamRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestRead
import com.altnautica.gcs.core.mavlink.messages.ParamSet
import com.altnautica.gcs.core.mavlink.messages.ParamValue as ParamValueMsg
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.WriteResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * most every 100 ms. A list download waits for 1.5 s of silence, then asks for
 * each missing index with `PARAM_REQUEST_READ`, up to three rounds.
 *
 * ArduPilot carries integers as C-cast floats; PX4 packs them bytewise into the
 * float, so the codec depends on the firmware family.
 */
class MavlinkParamService(
    scope: CoroutineScope,
    private val link: MavlinkLink,
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

    private val listLock = Mutex()
    private val writeLock = Mutex()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.messagesOf<ParamValueMsg>().collect { onValue(it) } }
        scope.launch {
            while (isActive) {
                if (dirty) publish()
                delay(PUBLISH_PERIOD_MS)
            }
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
        _snapshot.value = ParamSnapshot(count, byIndex.size, HashMap(values))
    }

    override suspend fun refresh(): ParamLoadResult = listLock.withLock {
        // A link opened a moment ago has not seen the vehicle heartbeat yet; give it a few beats.
        val target = link.target.value
            ?: withTimeoutOrNull(TARGET_WAIT_MS) { link.target.filterNotNull().first() }
            ?: return ParamLoadResult.Failed(REASON_NO_LINK)
        _loading.value = true
        try {
            values.clear()
            byIndex.clear()
            count = 0
            publish()
            loadAll(target)
        } finally {
            _loading.value = false
        }
    }

    private suspend fun loadAll(target: VehicleTarget): ParamLoadResult {
        send(ParamRequestList(target.sysId, target.compId))?.let { return ParamLoadResult.Failed(it) }
        var rounds = 0
        while (true) {
            awaitSilence()
            if (count == 0) {
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
            for (index in missing) {
                send(ParamRequestRead(target.sysId, target.compId, "", index))?.let { return ParamLoadResult.Failed(it) }
            }
        }
    }

    /** Returns once no PARAM_VALUE has arrived for [IDLE_MS]. */
    private suspend fun awaitSilence() {
        while (true) {
            val seen = arrivals.value
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
        const val SET_TIMEOUT_MS = 1_500L
        const val SET_RETRIES = 2
        const val TARGET_WAIT_MS = 5_000L
        const val REASON_NO_LINK = "noFcLink"
        const val REASON_TIMEOUT = "timeout"
        private const val UINT16_MAX = 0xFFFF

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
