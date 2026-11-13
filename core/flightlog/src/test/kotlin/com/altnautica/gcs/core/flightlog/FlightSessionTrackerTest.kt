package com.altnautica.gcs.core.flightlog

import com.altnautica.gcs.core.database.FlightSessionDao
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.core.database.TlogFileEntity
import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.MavlinkSigning
import com.altnautica.gcs.core.mavlink.messages.Attitude
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import com.altnautica.gcs.core.model.Attitude as AttitudeState
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.FenceStatus
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.LinkQuality
import com.altnautica.gcs.core.model.MissionProgress
import com.altnautica.gcs.core.model.ObstacleSectors
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.StatusText
import com.altnautica.gcs.core.model.SystemHealth
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.model.WindState
import com.altnautica.gcs.core.flightlog.tlog.TlogReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeTelemetry : VehicleTelemetry {
    override val attitude = MutableStateFlow<AttitudeState?>(null)
    override val position = MutableStateFlow<PositionFix?>(null)
    override val vfr = MutableStateFlow<Vfr?>(null)
    override val battery = MutableStateFlow<BatteryState?>(null)
    override val gps = MutableStateFlow<GpsState?>(null)
    override val heartbeat = MutableStateFlow<HeartbeatState?>(null)
    override val home = MutableStateFlow<HomePosition?>(null)
    override val wind = MutableStateFlow<WindState?>(null)
    override val ekf = MutableStateFlow<EkfState?>(null)
    override val vibration = MutableStateFlow<VibrationState?>(null)
    override val linkQuality = MutableStateFlow<LinkQuality?>(null)
    override val missionProgress = MutableStateFlow<MissionProgress?>(null)
    override val systemHealth = MutableStateFlow<SystemHealth?>(null)
    override val fence = MutableStateFlow<FenceStatus?>(null)
    override val obstacles = MutableStateFlow<ObstacleSectors?>(null)
    override val statusText = MutableSharedFlow<StatusText>(extraBufferCapacity = 16)

    fun armed(armed: Boolean) {
        heartbeat.value = HeartbeatState(armed, FlightMode(0, "STABILIZE"), vehicleType = 2, autopilot = 3, tMs = 0)
    }
}

private class Daos {
    val sessions = mutableListOf<FlightSessionEntity>()
    val files = mutableListOf<TlogFileEntity>()

    /** How many file-row inserts fail before they succeed again. */
    var failFileInserts = 0

    /** Rolls both tables back when the block throws, as a database transaction would. */
    val transaction = object : IndexTransaction {
        override suspend fun run(block: suspend () -> Unit) {
            val s = sessions.toList()
            val f = files.toList()
            try {
                block()
            } catch (e: Exception) {
                sessions.clear(); sessions += s
                files.clear(); files += f
                throw e
            }
        }
    }

    val sessionDao = object : FlightSessionDao {
        override suspend fun insert(session: FlightSessionEntity): Long {
            sessions += session.copy(id = sessions.size + 1L)
            return sessions.size.toLong()
        }
        override fun getAll(): Flow<List<FlightSessionEntity>> = flowOf(sessions.toList())
        override suspend fun getById(id: Long) = sessions.firstOrNull { it.id == id }
        override suspend fun delete(session: FlightSessionEntity) = Unit
        override suspend fun update(session: FlightSessionEntity) {
            sessions.replaceAll { if (it.id == session.id) session else it }
        }
    }

    val fileDao = object : TlogFileDao {
        override suspend fun insert(file: TlogFileEntity): Long {
            if (failFileInserts > 0) {
                failFileInserts--
                throw IllegalStateException("disk full")
            }
            files += file.copy(id = files.size + 1L)
            return files.size.toLong()
        }
        override fun observeAll(): Flow<List<TlogFileEntity>> = flowOf(files.toList())
        override fun observeForSession(sessionId: Long): Flow<List<TlogFileEntity>> = emptyFlow()
        override suspend fun listForSession(sessionId: Long) = files.filter { it.sessionId == sessionId }
        override suspend fun getById(id: Long): TlogFileEntity? = null
        override suspend fun updateSize(id: Long, sizeBytes: Long) {
            files.replaceAll { if (it.id == id) it.copy(sizeBytes = sizeBytes) else it }
        }
        override suspend fun delete(id: Long) = Unit
    }
}

class FlightSessionTrackerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val vehicle = MavlinkFrameWriter(sysId = 1, compId = 1)

    private fun frameOf(bytes: ByteArray): MavlinkFrame = MavlinkFrameParser().parse(bytes).single()

    @Test
    fun `the tracker starts its reconcile on an immediate dispatcher without crashing`() = runTest {
        val dir = tmp.newFolder("tlogs-immediate")
        java.io.File(dir, "2026-01-02_03-04-05_orphan.tlog").writeBytes(ByteArray(8))
        val daos = Daos()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        FlightSessionTracker(TlogRecorder({ dir }, StandardTestDispatcher(testScheduler)), daos.sessionDao, daos.fileDao, daos.transaction, scope)
        assertEquals(1, daos.files.size)
    }

    @Test
    fun `an armed period becomes a session row with a tlog of every received frame`() = runTest {
        val dir = tmp.newFolder("tlogs")
        val recorder = TlogRecorder({ dir }, StandardTestDispatcher(testScheduler))
        val daos = Daos()
        val tracker = FlightSessionTracker(recorder, daos.sessionDao, daos.fileDao, daos.transaction, backgroundScope)
        val telemetry = FakeTelemetry()
        val frames = MutableSharedFlow<MavlinkFrame>(extraBufferCapacity = 64)

        tracker.track(DeviceId("drone-1"), telemetry, frames, backgroundScope)
        telemetry.armed(false)
        runCurrent()
        frames.emit(frameOf(vehicle.encode(Heartbeat(type = 2, autopilot = 3)))) // before arming: not recorded

        telemetry.armed(true)
        runCurrent()
        val sent = listOf(
            vehicle.encode(Heartbeat(type = 2, autopilot = 3, baseMode = 128)),
            vehicle.encode(Attitude(roll = 0.1f)),
            vehicle.encode(VfrHud(groundspeed = 4f)),
        )
        sent.forEach { frames.emit(frameOf(it)) }
        telemetry.vfr.value = Vfr(airspeedMs = 0f, groundspeedMs = 4f, headingDeg = 0f, throttlePct = 0, climbMs = 0f, tMs = 0)
        runCurrent()

        telemetry.armed(false)
        // The tracker runs in backgroundScope, which advanceUntilIdle does not wait for.
        runCurrent()

        val session = daos.sessions.single()
        assertEquals(4f, session.maxSpeed)
        val file = daos.files.single()
        assertEquals(session.tlogPath, file.path)
        assertEquals("drone-1", file.deviceId)
        assertTrue(file.sizeBytes > 0)
        assertTrue(file.path.endsWith(".tlog") && "drone-1" in file.path)
        val records = TlogReader(java.io.File(file.path).inputStream()).asSequence().toList()
        assertEquals(sent.size, records.size)
        sent.zip(records).forEach { (bytes, record) -> assertArrayEquals(bytes, record.frame) }
    }

    @Test
    fun `frames queued at disarm are written before the file closes`() = runTest {
        val dir = tmp.newFolder("tlogs")
        val writer = StandardTestDispatcher(testScheduler)
        val recording = TlogRecorder({ dir }, writer).start("q")!!
        val bytes = vehicle.encode(Attitude(pitch = 0.2f))
        repeat(100) { recording.record(frameOf(bytes), timeUs = 1_760_000_000_000_000L + it) }
        // Nothing has been written yet: the writer has not run.
        val path = recording.close()
        assertEquals(100, TlogReader(java.io.File(path).inputStream()).asSequence().count())
    }

    @Test
    fun `signed frames are written back byte for byte`() {
        val signed = MavlinkFrameWriter(sysId = 1, compId = 1, signing = MavlinkSigning(ByteArray(32) { it.toByte() }, linkId = 3))
        val bytes = signed.encode(VfrHud(groundspeed = 7f))
        val record = TlogEncoding.record(42L, frameOf(bytes))
        assertArrayEquals(bytes, record.copyOfRange(8, record.size))
        assertEquals(42L, java.nio.ByteBuffer.wrap(record, 0, 8).long)
    }

    @Test
    fun `flights left open by a crash are closed and stray tlogs are indexed`() = runTest {
        val dir = tmp.newFolder("tlogs")
        val recorder = TlogRecorder({ dir }, StandardTestDispatcher(testScheduler))
        val daos = Daos()
        // A session opened at arm whose disarm update never ran.
        val openFile = java.io.File(dir, "2026-01-02_10-00-00_open.tlog").apply { writeBytes(ByteArray(64)) }
        openFile.setLastModified(1_000_000_000_000L + 90_000L)
        daos.sessionDao.insert(FlightSessionEntity(startTime = 1_000_000_000_000L, tlogPath = openFile.absolutePath))
        daos.fileDao.insert(
            TlogFileEntity(sessionId = 1, path = openFile.absolutePath, kind = com.altnautica.gcs.core.database.LogFileKind.Tlog, sizeBytes = 0, createdAtMs = 1_000_000_000_000L),
        )
        // A recording the index never heard of.
        val stray = java.io.File(dir, "2026-01-03_11-30-00_stray.tlog").apply { writeBytes(ByteArray(32)) }

        val tracker = FlightSessionTracker(recorder, daos.sessionDao, daos.fileDao, daos.transaction, backgroundScope)
        // Reconcile runs when the tracker starts, before any vehicle is tracked.
        runCurrent()
        assertEquals("interrupted", daos.sessions.first().notes)
        tracker.track(DeviceId("drone-1"), FakeTelemetry(), null, backgroundScope)
        runCurrent()

        val closed = daos.sessions.first()
        assertEquals(1_000_000_000_000L + 90_000L, closed.endTime)
        assertEquals(90, closed.durationSeconds)
        assertEquals("interrupted", closed.notes)
        assertEquals(64L, daos.files.first().sizeBytes)

        assertEquals(2, daos.sessions.size)
        val indexed = daos.sessions[1]
        assertEquals(stray.absolutePath, indexed.tlogPath)
        assertEquals("interrupted", indexed.notes)
        assertTrue(indexed.endTime > 0)
        val strayFile = daos.files.single { it.path == stray.absolutePath }
        assertEquals(indexed.id, strayFile.sessionId)
        assertEquals(32L, strayFile.sizeBytes)
    }

    @Test
    fun `a failed log index write at arm leaves one session and one file row`() = runTest {
        val dir = tmp.newFolder("tlogs")
        val recorder = TlogRecorder({ dir }, StandardTestDispatcher(testScheduler))
        val daos = Daos()
        daos.failFileInserts = 1
        val tracker = FlightSessionTracker(recorder, daos.sessionDao, daos.fileDao, daos.transaction, backgroundScope)
        val telemetry = FakeTelemetry()
        val frames = MutableSharedFlow<MavlinkFrame>(extraBufferCapacity = 64)

        tracker.track(DeviceId("drone-1"), telemetry, frames, backgroundScope)
        telemetry.armed(false)
        runCurrent()
        telemetry.armed(true)
        runCurrent()
        // The arm-time writes were rolled back together: no half-written session.
        assertTrue(daos.sessions.isEmpty())
        frames.emit(frameOf(vehicle.encode(Heartbeat(type = 2, autopilot = 3, baseMode = 128))))
        runCurrent()
        telemetry.armed(false)
        runCurrent()

        val session = daos.sessions.single()
        val file = daos.files.single()
        assertEquals(session.id, file.sessionId)
        assertEquals(session.tlogPath, file.path)
        assertTrue(file.sizeBytes > 0)
    }
}
