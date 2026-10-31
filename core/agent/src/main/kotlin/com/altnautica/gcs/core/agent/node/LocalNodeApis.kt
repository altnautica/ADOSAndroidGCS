package com.altnautica.gcs.core.agent.node

import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource

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
}
