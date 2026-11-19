package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PhoneRadioUplink
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * The pilot channel of a drone reached only over the phone radio: the same
 * JSON frames the pilot WebSocket carries, translated where the onboard
 * stream would translate them.
 *
 * Up: each `input`, `release`, `session.take`, `hb`, `bye` and `action`
 * frame becomes the `pilot.v1` msgpack body the stream sends the pilot, one
 * datagram each, never batched, addressed to the drone's flight controller.
 * Input frames are thinned to [inputHz] (the radio path's airtime lever; the
 * drone brakes after 300 ms without input). An action is answered here with
 * whether it left the phone, as the box answers it; the drone's verdict shows
 * in the state that follows. Down: each `state` the drone sends becomes the
 * `{kind:"pilot", ts_ms, data}` frame the stream sends a client.
 *
 * The link ends (with an [IOException], so the channel reconnects) when the
 * phone stops transmitting.
 */
internal class RadioPilotLink(
    private val uplink: PhoneRadioUplink,
    private val node: DeviceId,
    private val inputHz: () -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** When the next input frame may go: frames are thinned to [inputHz] on average, never in bursts. */
    @Volatile private var nextInputMs: Long? = null

    fun open(outgoing: Flow<String>): Flow<String> = channelFlow {
        if (!uplink.transmitting.value) throw IOException("the phone radio does not hold the uplink")
        launch {
            uplink.pilotFrom(node).collect { body -> toClient(body, nowMs())?.let { send(it) } }
        }
        launch {
            uplink.transmitting.first { !it }
            close(IOException("the phone radio uplink went down"))
        }
        outgoing.collect { text -> forward(text) }
    }

    private suspend fun ProducerScope<String>.forward(text: String) {
        val frame = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return
        val op = (frame["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val body = toPilot(frame)
        if (body == null) {
            if (op == "action") send(actionResult(frame, ok = false, err = "malformed"))
            return
        }
        if (op == "input") {
            val now = elapsedMs()
            val period = 1_000L / inputHz().coerceAtLeast(1)
            val due = nextInputMs
            if (due != null && now < due) return
            // On schedule, the next slot follows this one; after a pause, it follows now.
            nextInputMs = if (due == null || now - due >= period) now + period else due + period
        }
        val sent = uplink.sendPilot(node, body)
        if (op == "action") send(actionResult(frame, ok = sent, err = if (sent) null else "aux_unavailable"))
    }

    companion object {
        /** Ops a surface may send; the rest belong to on-board services. */
        val SURFACE_OPS = setOf("input", "release", "session.take", "hb", "bye", "action")

        /**
         * The `pilot.v1` body for a client frame, or null for an op a surface
         * may not send or a frame missing what its op needs.
         */
        fun toPilot(frame: JsonObject): ByteArray? {
            val op = frame.str("op") ?: return null
            if (op !in SURFACE_OPS) return null
            val ok = when (op) {
                "input" -> frame.str("src") != null && frame.uint("session") && frame.uint("seq") && frame.uint("t_ms") &&
                    (frame["axes"] as? JsonArray)?.let { a -> a.size == 4 && a.all { it.isNumber() } } == true && frame.uint("buttons")
                "release", "session.take" -> frame.str("src") != null && frame.uint("session")
                "action" -> frame.str("name") != null && frame.str("src") != null
                else -> frame["src"].let { it == null || it is JsonPrimitive && it.isString }
            }
            return if (ok) PilotMsgpack.encode(frame) else null
        }

        /** The client frame for a pilot message, or null for one a surface does not see. */
        fun toClient(body: ByteArray, nowMs: Long): String? {
            val msg = PilotMsgpack.decode(body) as? JsonObject ?: return null
            if (msg.str("op") != "state") return null
            val data = JsonObject(msg - "op")
            return buildJsonObject {
                put("kind", "pilot")
                put("ts_ms", nowMs)
                put("data", data)
            }.toString()
        }

        private fun actionResult(frame: JsonObject, ok: Boolean, err: String?): String = buildJsonObject {
            put("kind", "action_result")
            put("name", frame["name"] ?: JsonNull)
            put("id", frame["id"] ?: JsonNull)
            put("ok", ok)
            err?.let { put("err", it) }
        }.toString()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.uint(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toULongOrNull() != null

        private fun JsonElement.isNumber(): Boolean =
            this is JsonPrimitive && !isString && this !is JsonNull && content.toDoubleOrNull() != null
    }
}
