package com.altnautica.gcs.core.agent.video

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.node.AgentStatusRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.model.StreamSwitchKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What the caller does after [StreamCatalog.switch] succeeds. */
sealed interface StreamSwitchOutcome {
    /** Concurrent leg: dial `/whep?camera=<cameraId>`; nothing changed on the agent. */
    data class UseCameraQuery(val cameraId: String) : StreamSwitchOutcome

    /** The agent restarted its encoder on the chosen camera (~3 s); re-dial the same URL. */
    data object Switched : StreamSwitchOutcome
}

/**
 * The video streams a node offers. Concurrent legs come from
 * `/api/status/full` `video.streams[]` (more than one leg); otherwise, when the
 * camera roster has more than one assigned camera, each is a switchable leg on
 * the single encoder.
 */
@Singleton
class StreamCatalog @Inject constructor(
    private val clients: AgentClientFactory,
    private val status: AgentStatusRepository,
) {

    private val rosterRefresh = MutableStateFlow(0)

    fun streams(node: DeviceId): Flow<List<StreamDescriptor>> =
        combine(status.observe(node), roster(node)) { poll, roster -> describe(poll.status, roster) }
            .distinctUntilChanged()

    suspend fun switch(node: DeviceId, streamId: String): AgentResult<StreamSwitchOutcome> {
        val client = clients.forNode(node) ?: return AgentResult.Err(AgentError.Unauthorized)
        val current = streams(node).first()
        val target = current.firstOrNull { it.id == streamId } ?: return AgentResult.Err(AgentError.FeatureAbsent)
        if (target.switchKind == StreamSwitchKind.Concurrent) {
            return AgentResult.Ok(StreamSwitchOutcome.UseCameraQuery(streamId))
        }
        val roster = when (val r = client.cameraRoster()) {
            is AgentResult.Ok -> r.value
            is AgentResult.Err -> return r
        }
        val legs = CameraLegs.withPrimary(roster, streamId) ?: return AgentResult.Err(AgentError.FeatureAbsent)
        return when (val w = client.putCameraRoster(legs)) {
            is AgentResult.Ok -> {
                rosterRefresh.value++
                AgentResult.Ok(StreamSwitchOutcome.Switched)
            }
            is AgentResult.Err -> w
        }
    }

    /** The roster, re-read every [ROSTER_INTERVAL_MS] and right after a switch. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun roster(node: DeviceId): Flow<List<JsonObject>> = rosterRefresh.flatMapLatest {
        flow {
            while (true) {
                emit(clients.forNode(node)?.cameraRoster()?.getOrNull() ?: emptyList())
                delay(ROSTER_INTERVAL_MS)
            }
        }
    }

    companion object {
        const val ROSTER_INTERVAL_MS = 30_000L

        fun describe(status: StatusFull?, roster: List<JsonObject>): List<StreamDescriptor> {
            val legs = status?.video?.streams.orEmpty()
            if (legs.size > 1) {
                return legs.map {
                    StreamDescriptor(
                        id = it.id,
                        role = it.role.orEmpty(),
                        codec = it.codec.orEmpty(),
                        // Unsampled liveness is not dead.
                        live = it.live != false,
                        switchKind = StreamSwitchKind.Concurrent,
                    )
                }
            }
            val assigned = roster.filter { it.str("state") == "assigned" }
            if (assigned.size > 1) {
                return assigned.map {
                    StreamDescriptor(
                        id = it.str("id").orEmpty(),
                        role = it.str("role") ?: "camera",
                        codec = it.str("codec").orEmpty(),
                        live = (it["live"] as? JsonPrimitive)?.booleanOrNull != false,
                        switchKind = StreamSwitchKind.Switchable,
                    )
                }
            }
            return emptyList()
        }
    }
}

/** Builds the `PUT /api/video/roster` leg list from a roster read. */
internal object CameraLegs {
    private const val RESERVED_PRIMARY_ID = "main"
    private val NOT_DECLARED = setOf("plugin_owned", "discovered_unassigned")
    private val ALWAYS = listOf("id", "source", "purpose", "enabled", "role", "orientation", "name", "fov_deg", "mount_pitch_deg")
    private val WHEN_PRESENT = listOf("codec", "width", "height", "fps", "bitrate_kbps", "calibration", "match")

    /** The declared legs with [id] made the enabled primary and any other primary demoted. */
    fun withPrimary(roster: List<JsonObject>, id: String): List<JsonObject>? {
        val cam = roster.firstOrNull { it.str("id") == id } ?: return null
        val declared = roster.filter { it.str("state") !in NOT_DECLARED }.map(::leg)
        val patched = if (cam.str("state") !in NOT_DECLARED) {
            declared.map { if (it.str("id") == id) promote(it) else it }
        } else {
            declared + promote(leg(cam))
        }
        val taken = patched.mapNotNull { it.str("id") }
        return patched.map { l ->
            if (l.str("id") == id || l.str("role") != "primary") return@map l
            val demoted = JsonObject(l + ("role" to JsonNull))
            if (l.str("id") != RESERVED_PRIMARY_ID) {
                demoted
            } else {
                JsonObject(demoted + ("id" to JsonPrimitive(slug(l.str("name") ?: l.str("id")!!, taken - RESERVED_PRIMARY_ID))))
            }
        }
    }

    private fun promote(leg: JsonObject) = JsonObject(leg + ("role" to JsonPrimitive("primary")) + ("enabled" to JsonPrimitive(true)))

    private fun leg(row: JsonObject): JsonObject {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (k in ALWAYS) out[k] = row[k] ?: if (k == "purpose") JsonArray(emptyList()) else JsonNull
        for (k in WHEN_PRESENT) {
            val v = row[k]
            if (v != null && v !is JsonNull && !(v is JsonPrimitive && v.isString && v.content.isEmpty())) out[k] = v
        }
        return JsonObject(out)
    }

    fun slug(name: String, taken: List<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "ip-cam" }
        fun free(c: String) = c != RESERVED_PRIMARY_ID && c !in taken
        if (free(base)) return base
        return (2 until 1000).map { "$base-$it" }.first(::free)
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
