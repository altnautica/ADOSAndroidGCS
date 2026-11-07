package com.altnautica.gcs.core.agent.mission

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.MissionStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The vehicle's mission status as the agent publishes it (`mission` object). */
@Serializable
data class MissionStatusWire(
    val state: String = "none",
    val seq: Int = 0,
    val total: Int = 0,
    @SerialName("item_reached") val itemReached: Int? = null,
    val id: Long? = null,
    val source: String? = null,
    @SerialName("age_ms") val ageMs: Long? = null,
    @SerialName("landing_seq") val landingSeq: Boolean = false,
    @SerialName("uploads_seen") val uploadsSeen: Long? = null,
) {
    fun toModel(uploadsSeenFallback: Long? = null): MissionStatus = MissionStatus(
        state = MissionState.of(state) ?: MissionState.None,
        seq = seq,
        total = total,
        itemReached = itemReached,
        id = id,
        fromMissionState = source == "mission_state",
        ageMs = ageMs,
        landingSeq = landingSeq,
        uploadsSeen = uploadsSeen ?: uploadsSeenFallback,
    )
}

/** One mirrored item in MISSION_ITEM_INT field order; [x]/[y] are degrees × 1e7, [z] metres. */
@Serializable
data class MirrorItem(
    val seq: Int,
    val command: Int,
    val frame: Int = 0,
    val current: Int = 0,
    val autocontinue: Int = 1,
    val param1: Float = 0f,
    val param2: Float = 0f,
    val param3: Float = 0f,
    val param4: Float = 0f,
    val x: Int = 0,
    val y: Int = 0,
    val z: Float = 0f,
    @SerialName("mission_type") val missionType: Int = 0,
)

/** `GET /api/v1/mission`: the flight controller's actual plan as the agent mirrors it. */
@Serializable
data class MissionMirror(
    val rev: Long = 0,
    /** Wire item count, including the home slot on ArduPilot. */
    val count: Int = 0,
    @SerialName("home_slot") val homeSlot: Boolean = false,
    @SerialName("uploads_seen") val uploadsSeen: Long = 0,
    val mission: MissionStatusWire? = null,
    val items: List<MirrorItem> = emptyList(),
) {
    val status: MissionStatus? get() = mission?.toModel(uploadsSeen)
}

/** Mission mirror route of the agent. */
class MissionMirrorApi(private val client: AgentClient) {
    suspend fun mirror(): AgentResult<MissionMirror> = client.get(PATH, MissionMirror.serializer())

    companion object {
        const val PATH = "api/v1/mission"
    }
}
