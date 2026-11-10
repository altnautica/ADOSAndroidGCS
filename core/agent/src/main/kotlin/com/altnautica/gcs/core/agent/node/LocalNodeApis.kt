package com.altnautica.gcs.core.agent.node

import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import kotlinx.coroutines.flow.Flow

/**
 * Node pages served in-process instead of by an agent. Bound into a Hilt set;
 * the first entry that [handles] a node answers its capture, sensor and
 * preview requests, and the node's agent is never contacted.
 */
interface LocalNodeApis {
    fun handles(id: DeviceId): Boolean
    fun capture(id: DeviceId): CaptureApi?
    fun sensors(id: DeviceId): SensorsApi?
    fun capturePreview(id: DeviceId, camera: String?): VideoSource?

    /**
     * Autonomy feeds (detections, navigation stream, live map) served in-process for
     * [id]. Independent of [handles]: a simulated vehicle can serve these while its
     * other pages come from its own source. Null when this entry has none for [id].
     */
    fun autonomy(id: DeviceId): AutonomyFeedApi? = null

    /**
     * The flight-controller setup engine served in-process for [id] (the agent's
     * `/api/v1/fc-setup` routes and step stream). Independent of [handles], like [autonomy].
     */
    fun fcSetup(id: DeviceId): SetupBackend? = null

    /** Gimbal rate control served in-process for [id]; independent of [handles], like [autonomy]. */
    fun gimbal(id: DeviceId): com.altnautica.gcs.core.agent.gimbal.GimbalApi? = null

    /**
     * The agent's mission mirror (`GET /api/v1/mission`) served in-process for [id], so a
     * simulated vehicle reached straight over MAVLink still names its mission state.
     * Independent of [handles], like [autonomy].
     */
    fun missionMirror(id: DeviceId): Flow<MissionMirror?>? = null
}
