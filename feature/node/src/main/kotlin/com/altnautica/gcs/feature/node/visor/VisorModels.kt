package com.altnautica.gcs.feature.node.visor

import com.altnautica.gcs.core.agent.capture.CaptureExposure
import com.altnautica.gcs.core.agent.capture.CaptureSession
import com.altnautica.gcs.core.agent.capture.CaptureState
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.feature.node.common.PageProblem

/** Exposure state as the capture tab edits it. */
data class ExposureUi(val locked: Boolean, val exposureUs: Long?, val gain: Double?, val mode: String?, val applied: Boolean = false) {
    /** The lock was requested but the agent has not set it on the sensor yet. */
    val pendingApply: Boolean get() = locked && !applied

    fun toWire() = CaptureExposure(locked = locked, exposureUs = exposureUs, gain = gain, mode = mode)
}

enum class CaptureMode { Idle, Recording, Paused, Closing }

enum class ChipLevel { Ok, Warn, Bad }

/** One capture health chip (`blur`, `texture`, `flicker`, `corridor`, `storage`, `clock`). */
data class HealthChip(val id: String, val level: ChipLevel, val value: Double?)

/** The capture tab's view of `atlas/capture/state`. */
data class CaptureUi(
    val mode: CaptureMode,
    val sessionId: String?,
    val durationS: Double?,
    val site: String?,
    val floor: Int?,
    val room: String?,
    val segment: Int?,
    val framesTotal: Long?,
    val droppedPct: Double?,
    val poseSource: String?,
    val poseHealth: String?,
    val trackedFeatures: Int?,
    val coveragePct: Double?,
    val voxels: Long?,
    val loopClosed: Boolean?,
    val distanceFromStartM: Double?,
    val chips: List<HealthChip>,
    val exposure: ExposureUi?,
    val freeBytes: Long?,
    val estRemainingS: Double?,
    /** Capture cameras the session reports, in agent order; the preview picks from these. */
    val cameraIds: List<String>,
) {
    companion object {
        /** Chip order is fixed so the row does not reshuffle between polls. */
        val CHIP_ORDER = listOf("blur", "texture", "flicker", "corridor", "storage", "clock")

        fun from(s: CaptureState): CaptureUi = CaptureUi(
            mode = when (s.state) {
                "recording" -> CaptureMode.Recording
                "paused" -> CaptureMode.Paused
                "closing" -> CaptureMode.Closing
                else -> CaptureMode.Idle
            },
            sessionId = s.sessionId,
            durationS = s.durationS,
            site = s.site,
            floor = s.floor,
            room = s.room,
            segment = s.segment,
            framesTotal = s.frames?.total,
            droppedPct = s.frames?.droppedPct,
            poseSource = s.pose?.source,
            poseHealth = s.pose?.health,
            trackedFeatures = s.pose?.trackedFeatures,
            coveragePct = s.coverage?.roomPct,
            voxels = s.coverage?.voxels,
            loopClosed = s.coverage?.loopClosed,
            distanceFromStartM = s.coverage?.distanceFromStartM,
            chips = s.health
                .filter { it.id in CHIP_ORDER }
                .sortedBy { CHIP_ORDER.indexOf(it.id) }
                .map { HealthChip(it.id, level(it.level), it.value) },
            exposure = s.exposure?.let { ExposureUi(it.locked, it.exposureUs, it.gain, it.mode, it.applied) },
            freeBytes = s.storage?.freeBytes,
            estRemainingS = s.storage?.estRemainingS,
            cameraIds = s.frames?.perCamera.orEmpty().map { it.id },
        )

        /** An unknown level is shown as a warning, never as OK. */
        fun level(raw: String): ChipLevel = when (raw) {
            "ok" -> ChipLevel.Ok
            "bad" -> ChipLevel.Bad
            else -> ChipLevel.Warn
        }
    }
}

enum class UploadState { Idle, Queued, Uploading, Committed, Failed }

data class SessionUi(
    val id: String,
    val startedAtUtc: String?,
    val durationS: Double?,
    val sizeBytes: Long?,
    val rooms: List<String>,
    val floors: List<Int>,
    val uploaded: Boolean,
    val upload: UploadState,
    /** 0..1 while bytes are known; null when the agent did not say. */
    val progress: Float?,
    val uploadNode: String?,
    val uploadError: String?,
) {
    companion object {
        fun from(s: CaptureSession): SessionUi {
            val u = s.upload
            val total = u?.bytesTotal
            val sent = u?.bytesSent
            return SessionUi(
                id = s.sessionId,
                startedAtUtc = s.startedAtUtc,
                durationS = s.durationS,
                sizeBytes = s.sizeBytes,
                rooms = s.rooms,
                floors = s.floors,
                uploaded = s.uploaded,
                upload = when (u?.state) {
                    "queued" -> UploadState.Queued
                    "uploading" -> UploadState.Uploading
                    "committed" -> UploadState.Committed
                    "failed" -> UploadState.Failed
                    else -> UploadState.Idle
                },
                progress = if (total != null && total > 0 && sent != null) (sent.toFloat() / total).coerceIn(0f, 1f) else null,
                uploadNode = u?.node,
                uploadError = u?.error,
            )
        }
    }
}

/** Reconstruction presets offered on the sessions tab. */
enum class ReconstructPreset(val wire: String) { Preview("preview"), Hq("hq"), Tour("tour"), Warehouse("warehouse") }

enum class CaptureAction { Start, Stop, Pause, Resume, Mark, Exposure, Upload, Reconstruct, Delete }

/** Why a capture action was refused, specific enough to act on. */
sealed interface CaptureProblem {
    /** `E_STORAGE_FULL`: the capture volume is at its floor; new sessions are refused. */
    data object StorageFull : CaptureProblem
    data object AlreadyRecording : CaptureProblem
    /** `E_NOT_UPLOADED`: the session is not committed on the reconstruction node yet. */
    data object NotUploaded : CaptureProblem
    data object Armed : CaptureProblem
    data class Page(val problem: PageProblem) : CaptureProblem
    data class Other(val message: String?) : CaptureProblem

    companion object {
        fun from(error: AgentError): CaptureProblem {
            val code = (error as? AgentError.Server)?.let { it.errorCode ?: it.message }
            return when {
                code == "E_STORAGE_FULL" -> StorageFull
                code == "E_ALREADY_RECORDING" -> AlreadyRecording
                code == "E_NOT_UPLOADED" -> NotUploaded
                error is AgentError.Armed -> Armed
                error is AgentError.Server -> Other(error.message)
                else -> Page(PageProblem.from(error))
            }
        }
    }
}

sealed interface ActionState {
    data object Idle : ActionState
    data class Running(val action: CaptureAction) : ActionState
    data class Done(val action: CaptureAction, val detail: String? = null) : ActionState
    data class Failed(val action: CaptureAction, val problem: CaptureProblem) : ActionState
}
