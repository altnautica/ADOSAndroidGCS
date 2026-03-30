package com.altnautica.gcs.data.groundstation

data class StationStatus(
    val connected: Boolean = false,
    val droneConnected: Boolean = false,
    val videoActive: Boolean = false,
    val recording: Boolean = false,
    val uptime: Long = 0,
    val activeCamera: String = "cam0"
)

data class WfbStats(
    val rssi: Int = 0,
    val snr: Float = 0f,
    val packetLoss: Float = 0f,
    val fecRecovery: Float = 0f,
    val channel: Int = 0,
    val frequency: Int = 0,
    val bandwidth: Int = 0,
    val mcsIndex: Int = 0
)

data class VideoStats(
    val bitrate: Int = 0,
    val resolution: String = "",
    val fps: Int = 0,
    val codec: String = "h264",
    val keyframeInterval: Int = 0,
    val droppedFrames: Int = 0
)

data class RecordingInfo(
    val filename: String = "",
    val size: Long = 0,
    val duration: Long = 0,
    val timestamp: Long = 0,
    val resolution: String = "",
    val codec: String = ""
)

data class SystemInfo(
    val firmwareVersion: String = "",
    val socTemp: Float = 0f,
    val cpuUsage: Float = 0f,
    val memoryUsage: Float = 0f,
    val storageFree: Long = 0,
    val storageTotal: Long = 0,
    val hostname: String = "",
    val boardModel: String = ""
)

data class StationConfig(
    val wfbChannel: Int = 149,
    val wfbBandwidth: Int = 20,
    val wfbMcsIndex: Int = 3,
    val wfbFecK: Int = 8,
    val wfbFecN: Int = 12,
    val videoCodec: String = "h264",
    val videoBitrate: Int = 4000,
    val videoResolution: String = "1920x1080",
    val videoFps: Int = 30,
    val wifiSsid: String = "ADOS-GS-001",
    val wifiPassword: String = ""
)

data class CameraSwitchRequest(
    val cameraId: String
)

data class OtaRequest(
    val firmwareUrl: String,
    val version: String
)

data class AdapterInfo(
    val name: String = "",
    val driver: String = "",
    val chipset: String = "",
    val monitorMode: Boolean = false
)
