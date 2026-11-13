package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.BatteryHealth
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.LinkQuality
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.StatusText
import com.altnautica.gcs.core.model.SystemHealth
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.model.WindState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * An agent serving a drone in flight in process: the navigation stream (pose, scan, nav
 * state, the pilot and the AR and obstacle layers), the pilot channel, detection batches
 * and the battery engine, each fed by [LiveFlight] at the agent's own rates.
 */
internal class LiveAgent(private val now: () -> Long) : AutonomyFeedApi {
    val nav = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val pilot = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val batches = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Seconds to the reserve the battery engine reports; counts down in flight. */
    var reserveEtaS = 503L

    override fun detections(): Flow<String> = batches
    override fun navStream(outbound: Flow<String>): Flow<String> = nav
    override fun pilotStream(outgoing: Flow<String>): Flow<String> = pilot

    override suspend fun battery(): AgentResult<BatteryHealth> = AgentResult.Ok(
        Json { ignoreUnknownKeys = true }.decodeFromString(
            BatteryHealth.serializer(),
            """{"enabled":true,"stale":false,"updated_at_ms":${now()},"thresholds":{"reserve_percent":25},
               "packs":[{"id":0,"stale":false,"remaining_pct":92,"prediction":{"state":"discharging","eta_s":$reserveEtaS}}]}""",
        ),
    )

    private val absent = AgentResult.Err(AgentError.FeatureAbsent)
    override suspend fun visionDemand(body: JsonObject): AgentResult<JsonElement> = absent
    override suspend fun visionStatus(): AgentResult<JsonObject> = absent
    override suspend fun setDetector(modelId: String): AgentResult<JsonElement> = absent
    override suspend fun mapLive(since: Long): AgentResult<JsonObject> = absent

    /** This agent as the in-process source of every node's autonomy feeds. */
    val apis: Set<LocalNodeApis> = setOf(
        object : LocalNodeApis {
            override fun handles(id: DeviceId) = false
            override fun capture(id: DeviceId) = null
            override fun sensors(id: DeviceId) = null
            override fun capturePreview(id: DeviceId, camera: String?) = null
            override fun autonomy(id: DeviceId) = this@LiveAgent
        },
    )
}

/**
 * A drone hovering under the onboard pilot, as the bench flies it: telemetry on every topic
 * at the agent's flight rates (attitude and local position 50 Hz, position and GPS 5 Hz, VFR
 * 4 Hz, system status, EKF, vibration and wind 2 Hz, heartbeat and battery 1 Hz, link
 * quality every 500 ms and on each heartbeat), the pilot at 10 Hz on the pilot channel and
 * again on the navigation stream, pose 20 Hz, scan 10 Hz, nav state 5 Hz, AR and obstacle
 * layers 15 Hz, detections 10 Hz, and 30 fps video. Another surface (a controller) holds the
 * sticks, and a warning is unacknowledged, as on the bench.
 */
internal class LiveFlight(private val vehicle: FakeVehicle, val agent: LiveAgent, private val video: SimulatedVideo) {
    private val ar = fixture("ar-frame.json")
    private val obstacles = fixture("obstacles-frame.json")
    private val sources = mutableListOf<Source>()
    private var started = false

    private class Source(val periodMs: Long, val phaseMs: Long, val emit: (t: Long, n: Int) -> Unit) {
        var next = Long.MIN_VALUE
        var count = 0
    }

    private fun every(periodMs: Long, phaseMs: Long = 0, emit: (t: Long, n: Int) -> Unit) {
        sources += Source(periodMs, phaseMs, emit)
    }

    init {
        val tel = vehicle.telemetry
        every(20) { t, n ->
            val w = sin(n / 25.0).toFloat()
            tel.attitude.value = Attitude(0.04f * w, 0.02f * cos(n / 31.0).toFloat(), 4.4f + 0.002f * n, t)
        }
        every(20, 7) { t, n -> tel.localPosition.value = LocalPosition(0.002f * n, 0.1f * sin(n / 40.0).toFloat(), -2.5f, 0.1f, 0.02f, 0f, t) }
        every(200, 3) { t, n ->
            tel.position.value = PositionFix(47.397742 + n * 1e-7, 8.545594, 491f, 2.5f + 0.02f * sin(n / 3.0).toFloat(), 0.1f, 0.02f, -0.01f, t)
        }
        every(200, 11) { t, _ -> tel.gps.value = GpsState(GpsFix.Fix3D, 14, 0.7f, t) }
        every(250, 5) { t, n -> tel.vfr.value = Vfr(0.1f, 0.1f + 0.01f * (n % 3), 252f, 41, 0.02f * sin(n.toDouble()).toFloat(), t) }
        every(500, 13) { t, _ -> tel.systemHealth.value = SystemHealth(4, 0x3fffffL, 0x3fffffL, 0x3fffffL, t) }
        every(500, 17) { t, n -> tel.ekf.value = EkfState(0x1ff, 0.1f + 0.001f * (n % 5), 0.05f, 0.04f, 0.02f, t) }
        every(500, 19) { t, n -> tel.vibration.value = VibrationState(8f + (n % 4), 9f, 12f, 0, t) }
        every(500, 23) { t, _ -> tel.wind.value = WindState(270f, 1.5f, t) }
        every(500, 29) { t, _ ->
            val hb = tel.heartbeat.value
            tel.linkQuality.value = LinkQuality(-61, null, hb?.let { t - it.tMs } ?: 0)
        }
        every(1_000, 31) { t, _ ->
            vehicle.heartbeatAt(t, armed = true, mode = "GUIDED")
            tel.linkQuality.value = LinkQuality(-61, null, 0)
        }
        every(1_000, 37) { t, n ->
            tel.battery.value = BatteryState(0, 15.8f - 0.001f * n, 12f + (n % 3), 92 - n / 30, 503 - n, t)
            agent.reserveEtaS = 503L - n
        }
        // The agent's pilot clock is not the screen's beat: its frames land between beats.
        every(100, 43) { t, n -> agent.pilot.tryEmit(pilotFrame(t, n)) }
        every(100, 93) { t, n -> agent.nav.tryEmit(pilotFrame(t, n)) }
        every(50, 13) { t, n ->
            agent.nav.tryEmit("""{"kind":"pose","ts_ms":$t,"data":{"pose":{"R":[1,0,0,0,1,0,0,0,1],"t":[${0.002 * n},${0.1 * sin(n / 40.0)},-2.5]},"health":"good","frame":"map_ned"}}""")
        }
        every(100, 23) { t, n ->
            val ranges = (0 until 72).joinToString(",") { i -> "%.2f".format(6.0 + sin((i + n) / 9.0)) }
            agent.nav.tryEmit("""{"kind":"scan","ts_ms":$t,"data":{"angle_start_rad":-3.14159,"angle_increment_rad":0.0873,"ranges_m":[$ranges]}}""")
        }
        every(200, 41) { t, n ->
            agent.nav.tryEmit(
                """{"kind":"nav","ts_ms":$t,"data":{"mode":"gps","active_set":1,"extnav_health":"good","odom_rate_hz":${29.5 + n % 2},"obstacle_rate_hz":10.0,"min_obstacle_m":${3.0 + 0.1 * (n % 7)},"gps_fix":3,"pose_age_ms":${20 + n % 30}}}""",
            )
        }
        every(66, 17) { t, _ -> agent.nav.tryEmit("""{"kind":"ar","ts_ms":$t,"data":${stamped(ar, t)}}""") }
        every(66, 49) { t, _ -> agent.nav.tryEmit("""{"kind":"obstacles","ts_ms":$t,"data":${stamped(obstacles, t)}}""") }
        every(100, 61) { t, n ->
            agent.batches.tryEmit(
                """{"v":2,"model_id":"det","camera_id":"main","frame_id":$n,"ts_ms":$t,"frame_width":1280,"frame_height":720,
                   "detections":[{"bbox":{"x":${400 + n % 20},"y":300,"width":80,"height":160},"class_label":"person","confidence":0.82,"track_id":3}]}""",
            )
        }
        every(33) { _, _ -> video.frame() }
    }

    /** Emits everything due at [t]; the first call also sets home and the bench's warning. */
    fun step(t: Long) {
        if (!started) {
            started = true
            vehicle.telemetry.home.value = HomePosition(47.397742, 8.545594, 488.5f, t)
            vehicle.telemetry.statusText.tryEmit(StatusText(4, "GPS glitch", t))
        }
        for (s in sources) {
            if (s.next == Long.MIN_VALUE) s.next = t + s.phaseMs
            while (t >= s.next) {
                s.emit(t, s.count++)
                s.next += s.periodMs
            }
        }
    }

    private fun pilotFrame(t: Long, n: Int): String = livePilotFrame(t, n)
}

/**
 * A pilot frame of frame number [n] at [t]: the pilot holds a hover with a controller on the
 * sticks; the height above ground, the input age, the clearance and the setpoint change every
 * frame, as they do live.
 */
internal fun livePilotFrame(t: Long, n: Int): String {
    val agl = 2.5 + 0.06 * sin(n / 2.0)
    return """{"kind":"pilot","ts_ms":$t,"data":{"owner":"pilot","owner_label":"ADOS Pilot","skill":null,
        "vehicle_class":"multirotor","control_mode":"ados","profile":"normal","avoid":"brake","fc_mode":4,"fc_mode_name":"GUIDED",
        "armed":true,"landed":false,"vtol_transitioning":false,"input_src":"hid","input_age_ms":${10 + n % 40},"hold_reason":null,
        "avoid_state":"clear","min_clearance_m":${"%.2f".format(3.0 + 0.2 * sin(n / 5.0))},"goal_ned":null,"plan_ned":[],
        "setpoint_ned":[${"%.3f".format(0.002 * n)},0.0,-2.5],
        "readiness":[{"id":"fc_profile","ok":true,"severity":"block","msg":"FC failsafe parameters match"},{"id":"position","ok":true,"severity":"block","msg":"Position estimate available"}],
        "link_loss_in_s":null,"link_loss_action":"rth",
        "mission":{"state":"none","seq":0,"total":0,"item_reached":null,"id":null,"source":"fallback","age_ms":${n % 100},"landing_seq":false},
        "input_session":{"src":"hid","session":3,"label":"Controller"},"gate":null,"sequence":null,"banner":null,"rth_countdown_s":null,
        "surface_ops":["goto"],"agl_m":${"%.2f".format(agl)},"agl_source":"rangefinder","statustext":[],"failsafe_cause":null,"setup_step":null}}"""
}

private fun fixture(name: String): String = LiveFlight::class.java.classLoader!!.getResource("fixtures/$name")!!.readText()

/** [data] with its top-level `ts_ms` set to [t], so every layer frame is new. */
private fun stamped(data: String, t: Long): String = data.replaceFirst(Regex("\"ts_ms\":\\d+"), "\"ts_ms\":$t")
