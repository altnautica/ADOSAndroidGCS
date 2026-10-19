package com.altnautica.gcs.feature.node.demo

import com.altnautica.gcs.core.agent.capture.CameraFrames
import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.capture.CaptureCoverage
import com.altnautica.gcs.core.agent.capture.CaptureExposure
import com.altnautica.gcs.core.agent.capture.CaptureFrames
import com.altnautica.gcs.core.agent.capture.CaptureHealth
import com.altnautica.gcs.core.agent.capture.CaptureMarkResponse
import com.altnautica.gcs.core.agent.capture.CapturePose
import com.altnautica.gcs.core.agent.capture.CaptureSession
import com.altnautica.gcs.core.agent.capture.CaptureSessions
import com.altnautica.gcs.core.agent.capture.CaptureStartRequest
import com.altnautica.gcs.core.agent.capture.CaptureStartResponse
import com.altnautica.gcs.core.agent.capture.CaptureState
import com.altnautica.gcs.core.agent.capture.CaptureStorageLeft
import com.altnautica.gcs.core.agent.capture.ReconstructResponse
import com.altnautica.gcs.core.agent.capture.SessionUpload
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Demo-mode capture rig. Holds a small in-memory session lifecycle so every
 * control on the capture and sessions tabs does something visible; values are
 * seeds for demo mode only. Called from ViewModel scopes on the main thread.
 */
class DemoCaptureApi(private val now: () -> Long = System::currentTimeMillis) : CaptureApi {

    private var state = "idle"
    private var startedAtMs = 0L
    private var pausedMs = 0L
    private var pausedAtMs: Long? = null
    private var sessionId: String? = null
    private var site: String? = null
    private var floor = 0
    private var room: String? = null
    private var segment = 0
    private var exposure = CaptureExposure(locked = true, exposureUs = 4_000, gain = 2.0, mode = "walking")
    private val sessions = mutableListOf(
        session("demo-visor-20261019T091200Z-1a2b", 412.0, listOf("Lobby", "Hall"), uploaded = true, state = "committed"),
        session("demo-visor-20261019T094500Z-7c3d", 268.5, listOf("Dock", "Aisle 1", "Aisle 2"), uploaded = false, state = "idle"),
    )

    /** Seconds recorded, for the clock and sensor demo figures too. */
    val recordingS: Double
        get() {
            if (state == "idle") return 0.0
            val end = pausedAtMs ?: now()
            return (end - startedAtMs - pausedMs).coerceAtLeast(0) / 1000.0
        }

    override suspend fun state(): AgentResult<CaptureState> {
        if (state == "idle") return AgentResult.Ok(CaptureState(state = "idle", exposure = exposure, storage = storageLeft()))
        val s = recordingS
        val frames = (s * 20 * 2).toLong()
        return AgentResult.Ok(
            CaptureState(
                state = state,
                sessionId = sessionId,
                startedAtNs = startedAtMs * 1_000_000,
                durationS = s,
                site = site,
                floor = floor,
                room = room,
                segment = segment,
                frames = CaptureFrames(
                    total = frames,
                    droppedPct = 0.2,
                    perCamera = listOf(CameraFrames("cam_fl", 20.0, frames / 600), CameraFrames("cam_fr", 20.0, frames / 700)),
                ),
                pose = CapturePose(source = "vio", health = "ok", trackedFeatures = 143, driftHintM = 0.04),
                coverage = CaptureCoverage(
                    voxels = (s * 60).toLong(),
                    roomPct = (s * 1.5).coerceAtMost(96.0),
                    loopClosed = false,
                    distanceFromStartM = (s * 0.4) % 40.0,
                ),
                health = listOf(
                    CaptureHealth("blur", "ok", 182.0),
                    CaptureHealth("texture", if ((s.toLong() / 20) % 3 == 1L) "warn" else "ok", 54.0),
                    CaptureHealth("flicker", "ok", 0.8),
                    CaptureHealth("corridor", "ok", 0.12),
                    CaptureHealth("storage", "ok", 61.0),
                    CaptureHealth("clock", "ok", 0.42),
                ),
                exposure = exposure,
                storage = storageLeft(),
            ),
        )
    }

    override suspend fun start(request: CaptureStartRequest): AgentResult<CaptureStartResponse> {
        if (state != "idle") return AgentResult.Err(AgentError.Server(409, "E_ALREADY_RECORDING", "E_ALREADY_RECORDING"))
        state = "recording"
        startedAtMs = now()
        pausedMs = 0
        pausedAtMs = null
        site = request.site
        floor = request.floor ?: 0
        room = request.room
        segment = 0
        sessionId = "demo-visor-%d-%04x".format(startedAtMs / 1000, (startedAtMs and 0xffff).toInt())
        return AgentResult.Ok(CaptureStartResponse(ok = true, sessionId = sessionId))
    }

    override suspend fun stop(): AgentResult<JsonElement> {
        if (state == "idle") return ok()
        val id = sessionId ?: return ok()
        sessions.add(0, session(id, recordingS, listOfNotNull(room), uploaded = false, state = "idle"))
        state = "idle"
        sessionId = null
        return ok()
    }

    override suspend fun pause(): AgentResult<JsonElement> {
        if (state == "recording") {
            state = "paused"
            pausedAtMs = now()
        }
        return ok()
    }

    override suspend fun resume(): AgentResult<JsonElement> {
        if (state == "paused") {
            pausedMs += now() - (pausedAtMs ?: now())
            pausedAtMs = null
            state = "recording"
        }
        return ok()
    }

    override suspend fun mark(room: String, floor: Int): AgentResult<CaptureMarkResponse> {
        if (state == "idle") return AgentResult.Err(AgentError.Server(409, "E_NOT_RECORDING", "E_NOT_RECORDING"))
        this.room = room
        this.floor = floor
        segment += 1
        return AgentResult.Ok(CaptureMarkResponse(ok = true, segment = segment))
    }

    override suspend fun exposure(exposure: CaptureExposure): AgentResult<CaptureExposure> {
        this.exposure = exposure
        return AgentResult.Ok(exposure)
    }

    override suspend fun sessions(): AgentResult<CaptureSessions> {
        // An upload in flight advances a fixed step per read.
        sessions.replaceAll { s ->
            val u = s.upload ?: return@replaceAll s
            if (u.state != "uploading") return@replaceAll s
            val total = u.bytesTotal ?: 0
            val sent = ((u.bytesSent ?: 0) + total / 8).coerceAtMost(total)
            if (sent >= total) s.copy(uploaded = true, upload = u.copy(state = "committed", bytesSent = total)) else s.copy(upload = u.copy(bytesSent = sent))
        }
        return AgentResult.Ok(CaptureSessions(sessions.toList()))
    }

    override suspend fun upload(sessionId: String): AgentResult<JsonElement> {
        val i = sessions.indexOfFirst { it.sessionId == sessionId }
        if (i < 0) return AgentResult.Err(AgentError.FeatureAbsent)
        val s = sessions[i]
        sessions[i] = s.copy(upload = SessionUpload("uploading", 0, s.sizeBytes, NODE, null))
        return ok()
    }

    override suspend fun delete(sessionId: String, force: Boolean): AgentResult<JsonElement> {
        val s = sessions.firstOrNull { it.sessionId == sessionId } ?: return AgentResult.Err(AgentError.FeatureAbsent)
        if (!s.uploaded && !force) return AgentResult.Err(AgentError.Server(409, "E_NOT_UPLOADED", "E_NOT_UPLOADED"))
        sessions.remove(s)
        return ok()
    }

    override suspend fun reconstruct(sessionId: String, preset: String): AgentResult<ReconstructResponse> {
        val s = sessions.firstOrNull { it.sessionId == sessionId } ?: return AgentResult.Err(AgentError.FeatureAbsent)
        if (!s.uploaded) return AgentResult.Err(AgentError.Server(409, "E_NOT_UPLOADED", "E_NOT_UPLOADED"))
        return AgentResult.Ok(ReconstructResponse(ok = true, node = NODE, jobId = "j-demo-$preset"))
    }

    private fun storageLeft() = CaptureStorageLeft(freeBytes = 812_345_678_901 - (recordingS * 150_000_000).toLong(), estRemainingS = 14_400)

    private fun ok(): AgentResult<JsonElement> = AgentResult.Ok(JsonObject(mapOf("ok" to JsonPrimitive(true))))

    private companion object {
        const val NODE = "workstation-1"

        fun session(id: String, durationS: Double, rooms: List<String>, uploaded: Boolean, state: String): CaptureSession {
            val size = (durationS * 150_000_000).toLong()
            return CaptureSession(
                sessionId = id,
                startedAtUtc = null,
                durationS = durationS,
                sizeBytes = size,
                frames = (durationS * 40).toLong(),
                rooms = rooms,
                floors = listOf(0),
                timebase = "hub_freerun",
                anchorSource = "operator",
                uploaded = uploaded,
                upload = SessionUpload(state, if (uploaded) size else 0, size, if (uploaded) NODE else null, null),
            )
        }
    }
}
