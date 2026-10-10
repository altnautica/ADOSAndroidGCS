package com.altnautica.gcs.core.extensions.bridge

import com.altnautica.gcs.core.extensions.data.ApiResult
import com.altnautica.gcs.core.extensions.data.ExtensionReach
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.ExtensionScope
import com.altnautica.gcs.core.extensions.data.ExtensionStateFeed
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.extensions.marks.CockpitMarksParser
import com.altnautica.gcs.core.extensions.marks.CockpitMarksStore
import com.altnautica.gcs.core.extensions.mission.ExtensionMissionPort
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private fun ok(extra: JsonObjectBuilderAction = {}): JsonObject = buildJsonObject {
    put("ok", true)
    extra()
}

private fun fail(error: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", error)
}

private typealias JsonObjectBuilderAction = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit

internal fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonElement?.string(key: String): String? =
    (obj()[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.number(key: String): Double? =
    (obj()[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

/** The bridge handlers for one mounted extension frame, plus their teardown. */
class MountHandlers internal constructor(
    val handlers: Map<String, BridgeHandler>,
    private val onDispose: () -> Unit,
) {
    fun dispose() = onDispose()
}

/**
 * Builds the method handlers of one extension bound to one node. Capability checks are
 * already done by [EnvelopeDispatcher]; handlers enforce argument shape, operator
 * confirmation, rate limits and scope (an extension only ever touches its own config and
 * its bound node).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ExtensionHandlersFactory @Inject constructor(
    private val vehicles: VehicleProvider,
    private val repository: ExtensionRepository,
    private val stateFeed: ExtensionStateFeed,
    private val perception: PerceptionFeed,
    private val bus: ExtensionEventBus,
    private val marks: CockpitMarksStore,
    private val confirm: ConfirmBroker,
    private val notifier: ExtensionNotifier,
    private val mission: ExtensionMissionPort,
    @ExtensionScope private val appScope: CoroutineScope,
) {
    private val rates = ConcurrentHashMap<String, CommandRateLimiter>()
    private val recordingOwners = ConcurrentHashMap<DeviceId, String>()

    fun create(pluginId: String, node: DeviceId, mountId: String): MountHandlers {
        val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
        val telemetrySubs = ConcurrentHashMap<String, () -> Unit>()
        val eventSubs = ConcurrentHashMap<String, () -> Unit>()
        var perceptionSub: Job? = null
        var perceptionRelease: (() -> Unit)? = null
        val markSource = "extension:$pluginId:$mountId"
        val rate = rates.getOrPut(pluginId) { CommandRateLimiter() }

        suspend fun vehicle(): Vehicle? = withTimeoutOrNull(VEHICLE_WAIT_MS) { vehicles.vehicle(node).first() }

        val h = HashMap<String, BridgeHandler>()

        h["ping"] = { ok() }
        // No extension locale bundles reach this app; the key is returned as-is.
        h["i18n.t"] = { args ->
            JsonPrimitive(args.string("key") ?: throw BridgeException("schema_invalid", "i18n.t requires a string key"))
        }

        h["notify"] = { args ->
            if (notifier.post(pluginId, args.string("message").orEmpty(), NoticeLevel.Info)) ok() else fail("rate_limited")
        }
        h["notification.publish"] = { args ->
            val message = listOfNotNull(args.string("title") ?: args.string("message"), args.string("body"))
                .filter { it.isNotBlank() }.joinToString(": ")
            val level = when (args.string("severity")) {
                "critical", "error" -> NoticeLevel.Error
                "warning" -> NoticeLevel.Warning
                "success" -> NoticeLevel.Success
                else -> NoticeLevel.Info
            }
            if (notifier.post(pluginId, message, level)) ok() else fail("rate_limited")
        }

        h["telemetry.subscribe"] = { args ->
            val topic = args.string("topic")?.takeIf { it.isNotEmpty() }
                ?: throw BridgeException("schema_invalid", "telemetry topic must be a non-empty string")
            val method = "telemetry.$topic"
            val cap = capability.orEmpty()
            telemetrySubs.remove(topic)?.invoke()
            if (TelemetryTopics.isVehicleTopic(topic)) {
                val job = scope.launch {
                    vehicles.vehicle(node)
                        .flatMapLatest { v -> v?.let { TelemetryTopics.flow(topic, it.telemetry) } ?: emptyFlow() }
                        .collect { postEvent(method, cap, it) }
                }
                telemetrySubs[topic] = { job.cancel() }
                ok { put("linked", true) }
            } else {
                // The extension's own agent-extended channel, published in its state.
                val origin = ExtensionEventBus.agentStateOrigin(pluginId, node)
                val off = bus.subscribe(method) { _, payload, from -> if (from == origin) postEvent(method, cap, payload) }
                val release = stateFeed.acquire(node)
                telemetrySubs[topic] = {
                    off()
                    release()
                }
                ok()
            }
        }
        h["telemetry.unsubscribe"] = { args ->
            val topic = args.string("topic")
            val off = topic?.let { telemetrySubs.remove(it) }
            if (off == null) fail("not subscribed") else ok().also { off() }
        }

        h["events.subscribe"] = handler@{ args ->
            val topic = args.string("topic") ?: return@handler fail("events.subscribe requires a topic")
            val cap = capability.orEmpty()
            eventSubs.remove(topic)?.invoke()
            eventSubs[topic] = bus.subscribe(topic) { t, payload, origin ->
                if (!ExtensionEventBus.isReservedTopic(t) && !ExtensionEventBus.isAgentStateOrigin(origin)) {
                    postEvent(t, cap, payload)
                }
            }
            ok()
        }
        h["events.unsubscribe"] = { args ->
            val off = args.string("topic")?.let { eventSubs.remove(it) }
            if (off == null) fail("not subscribed") else ok().also { off() }
        }
        h["events.publish"] = handler@{ args ->
            val topic = args.string("topic") ?: return@handler fail("events.publish requires a topic")
            val prefix = "plugin.$pluginId."
            if (!topic.startsWith(prefix) || topic.length == prefix.length) {
                fail("events.publish: an extension publishes only under $prefix*")
            } else {
                val payload = args.obj()["payload"]
                if ((payload?.toString()?.length ?: 0) > MAX_EVENT_PAYLOAD_BYTES) {
                    fail("events.publish: payload exceeds $MAX_EVENT_PAYLOAD_BYTES bytes")
                } else {
                    bus.publish(topic, payload, pluginId)
                    ok()
                }
            }
        }

        h["cockpit.marks"] = { args ->
            val parsed = CockpitMarksParser.parse(args.obj()["marks"])
            marks.set(node, markSource, parsed)
            ok { put("count", parsed.size) }
        }
        h["cockpit.marks.clear"] = {
            marks.clear(node, markSource)
            ok()
        }

        h["perception.read"] = {
            when (val r = repository.api(node)?.perceptionInfo()) {
                is ApiResult.Ok -> buildJsonObject {
                    put("tier", r.value.tier?.let(::JsonPrimitive) ?: JsonNull)
                    put("offloadTarget", JsonNull)
                    put("npuTops", r.value.npuTops?.let(::JsonPrimitive) ?: JsonNull)
                    put("hasAccelerator", r.value.npuTops?.let { JsonPrimitive(it > 0) } ?: JsonNull)
                }
                else -> buildJsonObject {
                    put("tier", JsonNull)
                    put("offloadTarget", JsonNull)
                    put("npuTops", JsonNull)
                    put("hasAccelerator", JsonNull)
                }
            }
        }
        h["perception.subscribe"] = {
            perceptionSub?.cancel()
            perceptionRelease?.invoke()
            val release = perception.acquire(node)
            perceptionRelease = release
            val cap = capability.orEmpty()
            val flow = perception.batches(node)
            perceptionSub = flow?.let { f -> scope.launch { f.collect { postEvent("perception.detections", cap, it.payload) } } }
            ok()
        }
        h["perception.unsubscribe"] = {
            val had = perceptionRelease != null
            perceptionSub?.cancel()
            perceptionSub = null
            perceptionRelease?.invoke()
            perceptionRelease = null
            if (had) ok() else buildJsonObject { put("ok", false) }
        }
        h["perception.health"] = {
            val now = perception.clock()
            val latest = perception.latest(node)
            val feed = when {
                latest == null -> "idle"
                now - latest.receivedAtMs <= PerceptionFeed.STALE_MS -> "fresh"
                else -> "stale"
            }
            val tier = (repository.api(node)?.perceptionInfo() as? ApiResult.Ok)?.value?.tier
            val session = when (feed) {
                "fresh" -> "live"
                "stale" -> "stalled"
                else -> if (tier == "local" || tier == "offload" || tier == "hybrid") "opening" else "closed"
            }
            val bps = PerceptionFeed.batchesPerSecond(perception.receiptTimes(node), now, PerceptionFeed.THROUGHPUT_WINDOW_MS)
            buildJsonObject {
                put("session", session)
                put("feed", feed)
                put("ageMs", latest?.let { JsonPrimitive((now - it.receivedAtMs).coerceAtLeast(0)) } ?: JsonNull)
                put("batchesPerSecond", bps?.let(::JsonPrimitive) ?: JsonNull)
                put("boundNode", JsonNull)
            }
        }

        h["command.send"] = handler@{ args ->
            val command = args.string("command") ?: return@handler fail("command.send requires a command name")
            val inner = args.obj()["args"]
            when (command) {
                MethodRules.PLUGIN_CONFIG_WRITE -> {
                    val key = inner.string("key")?.takeIf { it.isNotEmpty() }
                        ?: return@handler fail("plugin.config.write requires a string key")
                    when (val w = repository.writeConfig(node, pluginId, key, inner.obj()["value"] ?: JsonNull)) {
                        WriteOutcome.Done -> ok {
                            put("result", buildJsonObject {
                                put("set", true)
                                put("key", key)
                            })
                        }
                        is WriteOutcome.NoPath -> fail(w.reasonKey)
                        is WriteOutcome.Failed -> fail(w.failure.toString())
                    }
                }
                MethodRules.VISION_DESIGNATE -> designate(pluginId, node, inner, rate, ::vehicle)
                else -> vehicleCommand(pluginId, node, command, inner, rate, ::vehicle)
            }
        }

        h["mission.read"] = { mission.read(node) }
        h["mission.write"] = { args ->
            mission.write(pluginId, node, args) { title, body, critical -> confirm.request(pluginId, title, body, critical) }
        }

        h["recording.start"] = {
            val owner = recordingOwners.putIfAbsent(node, pluginId)
            if (owner != null) {
                fail("already recording")
            } else {
                when (val r = repository.api(node)?.startRecording()) {
                    is ApiResult.Ok -> ok()
                    is ApiResult.Err -> fail(r.failure.toString()).also { recordingOwners.remove(node, pluginId) }
                    null -> fail(ExtensionReach.REASON_NO_AGENT).also { recordingOwners.remove(node, pluginId) }
                }
            }
        }
        h["recording.stop"] = {
            if (recordingOwners[node] != pluginId) {
                fail("not recording")
            } else {
                when (val r = repository.api(node)?.stopRecording()) {
                    is ApiResult.Ok -> ok().also { recordingOwners.remove(node, pluginId) }
                    is ApiResult.Err -> fail(r.failure.toString())
                    null -> fail(ExtensionReach.REASON_NO_AGENT)
                }
            }
        }
        h["recording.mark"] = {
            if (recordingOwners[node] != pluginId) fail("not recording") else fail(UNSUPPORTED)
        }

        for (m in listOf("cloud.read", "records.list", "records.get", "records.put", "records.remove")) {
            h[m] = { throw BridgeException(UNSUPPORTED, "$m is not available on this platform") }
        }

        return MountHandlers(h) {
            telemetrySubs.values.forEach { it() }
            telemetrySubs.clear()
            eventSubs.values.forEach { it() }
            eventSubs.clear()
            perceptionSub?.cancel()
            perceptionRelease?.invoke()
            marks.clear(node, markSource)
            if (recordingOwners[node] == pluginId) {
                appScope.launch {
                    repository.api(node)?.stopRecording()
                    recordingOwners.remove(node, pluginId)
                }
            }
            scope.cancel()
        }
    }

    private suspend fun approve(
        pluginId: String,
        before: Vehicle?,
        title: String,
        body: String,
        rate: CommandRateLimiter,
        vehicle: suspend () -> Vehicle?,
    ): String? {
        if (!rate.available()) return RATE_LIMITED
        val armed = before?.telemetry?.heartbeat?.value?.armed
        val suffix = when (armed) {
            true -> " while the vehicle is ARMED"
            false -> ""
            null -> " while the vehicle's arm state is UNKNOWN"
        }
        if (!confirm.request(pluginId, title, body + suffix, critical = armed != false)) return "declined by operator"
        val after = vehicle()
        if (after?.telemetry?.heartbeat?.value?.armed != armed || (after == null) != (before == null)) {
            return "the drone's arm or link state changed while awaiting approval"
        }
        if (!rate.take()) return RATE_LIMITED
        return null
    }

    private suspend fun vehicleCommand(
        pluginId: String,
        node: DeviceId,
        command: String,
        args: JsonElement?,
        rate: CommandRateLimiter,
        vehicle: suspend () -> Vehicle?,
    ): JsonObject {
        val cmd: VehicleCommand = when (command) {
            "takeoff" -> {
                val alt = args.number("alt") ?: return fail("invalid args for 'takeoff'")
                VehicleCommand.Takeoff(alt.coerceIn(1.0, 120.0).toFloat())
            }
            "land" -> VehicleCommand.Land
            "rtl" -> VehicleCommand.ReturnToLaunch
            else -> return fail("command '$command' is not permitted from extensions")
        }
        val before = vehicle() ?: return fail("command.send not supported")
        approve(pluginId, before, "Extension command", "$pluginId wants to send \"$command\" to ${node.value}", rate, vehicle)
            ?.let { return fail(it) }
        val result = before.control.run(cmd)
        if (result != CommandResult.Accepted) {
            notifier.post(pluginId, "approved \"$command\" for ${node.value} was not sent ($result)", NoticeLevel.Error)
        }
        return buildJsonObject {
            put("ok", result == CommandResult.Accepted)
            put("result", result.toString())
        }
    }

    private suspend fun designate(
        pluginId: String,
        node: DeviceId,
        p: JsonElement?,
        rate: CommandRateLimiter,
        vehicle: suspend () -> Vehicle?,
    ): JsonObject {
        val camera = p.string("camera_id")
        val bbox = p.obj()["bbox"]
        val box = listOf("x", "y", "width", "height").map { bbox.number(it) }
        if (camera == null || box.any { it == null }) return fail("vision.designate requires camera_id + a numeric bbox")
        val api = repository.api(node) ?: return fail("no agent link to this node")
        val before = vehicle()
        approve(
            pluginId, before, "Extension follow target",
            "$pluginId wants to change the tracked follow target of ${node.value}", rate, vehicle,
        )?.let { return fail(it) }
        val body = buildJsonObject {
            put("camera_id", camera)
            put("bbox", buildJsonObject {
                put("x", box[0]!!)
                put("y", box[1]!!)
                put("width", box[2]!!)
                put("height", box[3]!!)
            })
            p.string("class_label")?.let { put("class_label", it) }
            p.number("confidence")?.let { put("confidence", it) }
        }
        return when (val r = api.designate(body)) {
            is ApiResult.Ok -> {
                val designated = (r.value.obj()["designated"] as? JsonPrimitive)?.content == "true"
                buildJsonObject {
                    put("ok", designated)
                    put("result", r.value)
                }
            }
            is ApiResult.Err -> fail(r.failure.toString())
        }
    }

    companion object {
        const val UNSUPPORTED = "unsupported_on_platform"
        const val RATE_LIMITED = "command rate limit exceeded"
        const val MAX_EVENT_PAYLOAD_BYTES = 64 * 1024
        private const val VEHICLE_WAIT_MS = 1_000L
    }
}
