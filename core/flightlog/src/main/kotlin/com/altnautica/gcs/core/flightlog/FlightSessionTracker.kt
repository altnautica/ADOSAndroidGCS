package com.altnautica.gcs.core.flightlog

import android.util.Log
import com.altnautica.gcs.core.database.FlightSessionDao
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.database.LogFileKind
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.core.database.TlogFileEntity
import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracks flight sessions by observing a vehicle's armed state.
 *
 * On arm: opens a tlog recording fed with every frame the vehicle's link receives, inserts
 * the session (open, `endTime = 0`) and its log file into the index, and begins tracking max
 * altitude, speed, distance from home, and total path distance.
 *
 * On disarm, or when the tracking job ends while armed: closes the tlog (after its queued
 * frames are written) and updates the session row with its final stats and the file size.
 *
 * A process death mid-flight skips that update. When the tracker starts, before any session,
 * it closes any session left open and indexes any `.tlog` file the index does not know, each
 * as an interrupted flight, so Logs shows the recording and Delete can remove it.
 */
@Singleton
class FlightSessionTracker internal constructor(
    private val tlogRecorder: TlogRecorder,
    private val flightSessionDao: FlightSessionDao,
    private val tlogFileDao: TlogFileDao,
    private val transaction: IndexTransaction,
    scope: CoroutineScope,
) {
    @Inject constructor(
        tlogRecorder: TlogRecorder,
        flightSessionDao: FlightSessionDao,
        tlogFileDao: TlogFileDao,
        transaction: IndexTransaction,
    ) : this(tlogRecorder, flightSessionDao, tlogFileDao, transaction, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    init {
        scope.launch { reconcileOnce() }
    }

    private companion object {
        const val TAG = "FlightSessionTracker"
        const val EARTH_RADIUS_M = 6371000.0
        const val MAX_SEGMENT_M = 1000.0
        const val INTERRUPTED = "interrupted"

        /** The timestamp that starts every tlog file name ([TlogRecorder]). */
        const val FILE_STAMP = "yyyy-MM-dd_HH-mm-ss"
    }

    private val reconcileLock = Mutex()
    private var reconciled = false

    private sealed interface Sample {
        data class Position(val fix: PositionFix) : Sample
        data class Speed(val groundspeedMs: Float) : Sample
    }

    /** Accumulated stats for one armed period. Only touched by its own collector. */
    private class Session(val startTime: Long, val batteryStart: Int, val sessionId: Long?, val fileId: Long?) {
        var maxAltitude = 0f
        var maxSpeed = 0f
        var maxDistance = 0f
        var totalDistance = 0f
        var lastLat = 0.0
        var lastLon = 0.0
        var hasLastPosition = false
    }

    /**
     * Track [telemetry] for [vehicleId] until the returned job, or [scope], is cancelled.
     * [frames] is the link's receive path; null (a vehicle fed from an agent snapshot)
     * records the session without a tlog.
     */
    fun track(vehicleId: DeviceId, telemetry: VehicleTelemetry, frames: Flow<MavlinkFrame>?, scope: CoroutineScope): Job =
        scope.launch {
            reconcileOnce()
            telemetry.heartbeat
                .filterNotNull()
                .map { it.armed }
                .distinctUntilChanged()
                .collectLatest { armed ->
                    if (armed) runSession(vehicleId, telemetry, frames)
                }
        }

    /** Runs for one armed period; cancelled by the next disarm. */
    private suspend fun runSession(vehicleId: DeviceId, telemetry: VehicleTelemetry, frames: Flow<MavlinkFrame>?) = coroutineScope {
        Log.i(TAG, "${vehicleId.value} armed, starting flight session")
        val startTime = System.currentTimeMillis()
        val batteryStart = telemetry.battery.value?.remainingPct ?: -1
        val recording = frames?.let { tlogRecorder.start(vehicleId.value) }
        val (sessionId, fileId) = open(vehicleId, startTime, batteryStart, recording?.file)
        val session = Session(startTime, batteryStart, sessionId, fileId)
        val tap = if (recording != null && frames != null) {
            launch(start = CoroutineStart.UNDISPATCHED) { frames.collect(recording::record) }
        } else {
            null
        }
        try {
            merge(
                telemetry.position.filterNotNull().map { Sample.Position(it) },
                telemetry.vfr.filterNotNull().map { Sample.Speed(it.groundspeedMs) },
            ).collect { sample ->
                when (sample) {
                    is Sample.Position -> onPosition(session, sample.fix, telemetry)
                    is Sample.Speed -> {
                        session.maxSpeed = max(session.maxSpeed, sample.groundspeedMs)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                tap?.cancel()
                val tlogPath = recording?.close().orEmpty()
                finish(vehicleId, session, telemetry, tlogPath)
            }
        }
    }

    private fun onPosition(session: Session, pos: PositionFix, telemetry: VehicleTelemetry) {
        session.maxAltitude = max(session.maxAltitude, pos.altRelM)

        // Distance from home
        val home = telemetry.home.value
        if (home != null && home.latDeg != 0.0) {
            val dist = haversineMeters(home.latDeg, home.lonDeg, pos.latDeg, pos.lonDeg)
            session.maxDistance = max(session.maxDistance, dist.toFloat())
        }

        // Total distance (cumulative path), ignoring GPS jumps over 1 km
        if (session.hasLastPosition && pos.latDeg != 0.0) {
            val segDist = haversineMeters(session.lastLat, session.lastLon, pos.latDeg, pos.lonDeg)
            if (segDist < MAX_SEGMENT_M) {
                session.totalDistance += segDist.toFloat()
            }
        }
        if (pos.latDeg != 0.0) {
            session.lastLat = pos.latDeg
            session.lastLon = pos.lonDeg
            session.hasLastPosition = true
        }
    }

    /**
     * Inserts the open session and its log file at arm, in one transaction; null ids when the
     * index could not be written (neither row then exists, and [finish] writes both).
     */
    private suspend fun open(vehicleId: DeviceId, startTime: Long, batteryStart: Int, tlog: File?): Pair<Long?, Long?> = try {
        var sessionId: Long? = null
        var fileId: Long? = null
        transaction.run {
            val tlogPath = tlog?.absolutePath.orEmpty()
            val id = flightSessionDao.insert(
                FlightSessionEntity(startTime = startTime, batteryStart = batteryStart, tlogPath = tlogPath),
            )
            fileId = tlog?.let { tlogFileDao.insert(tlogRow(vehicleId, id, tlogPath, startTime)) }
            sessionId = id
        }
        sessionId to fileId
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open flight session: ${e.message}", e)
        null to null
    }

    private fun tlogRow(vehicleId: DeviceId, sessionId: Long, path: String, startTime: Long) = TlogFileEntity(
        sessionId = sessionId,
        path = path,
        kind = LogFileKind.Tlog,
        sizeBytes = 0,
        createdAtMs = startTime,
        deviceId = vehicleId.value,
    )

    private suspend fun finish(vehicleId: DeviceId, session: Session, telemetry: VehicleTelemetry, tlogPath: String) {
        Log.i(TAG, "${vehicleId.value} ending flight session")

        val endTime = System.currentTimeMillis()
        val durationSec = ((endTime - session.startTime) / 1000).toInt()

        val entity = FlightSessionEntity(
            startTime = session.startTime,
            endTime = endTime,
            durationSeconds = durationSec,
            maxAltitude = session.maxAltitude,
            maxSpeed = session.maxSpeed,
            maxDistance = session.maxDistance,
            totalDistance = session.totalDistance,
            batteryStart = session.batteryStart,
            batteryEnd = telemetry.battery.value?.remainingPct ?: -1,
            tlogPath = tlogPath,
        )

        try {
            transaction.run {
                // Null ids: the inserts at arm failed, so both rows are written now.
                val sessionId = session.sessionId?.also { flightSessionDao.update(entity.copy(id = it)) }
                    ?: flightSessionDao.insert(entity)
                if (tlogPath.isNotEmpty()) {
                    val file = File(tlogPath)
                    val fileId = session.fileId ?: tlogFileDao.insert(tlogRow(vehicleId, sessionId, tlogPath, session.startTime))
                    tlogFileDao.updateSize(fileId, if (file.exists()) file.length() else 0)
                }
            }
            Log.i(TAG, "Flight session saved: ${durationSec}s, maxAlt=${session.maxAltitude}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save flight session: ${e.message}", e)
        }
    }

    private suspend fun reconcileOnce() = reconcileLock.withLock {
        if (reconciled) return@withLock
        reconciled = true
        try {
            reconcile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconcile interrupted flights: ${e.message}", e)
        }
    }

    /**
     * Closes sessions a process death left open (at their tlog's last write) and indexes every
     * `.tlog` file the index does not know as an interrupted flight. Runs before any session
     * of this process starts, so no file is still being recorded.
     */
    private suspend fun reconcile() {
        val indexed = tlogFileDao.observeAll().firstOrNull().orEmpty()
        flightSessionDao.getAll().firstOrNull().orEmpty().filter { it.endTime == 0L }.forEach { open ->
            val file = File(open.tlogPath)
            val end = if (open.tlogPath.isNotEmpty() && file.exists()) file.lastModified() else open.startTime
            flightSessionDao.update(open.copy(endTime = end, durationSeconds = ((end - open.startTime) / 1000).toInt(), notes = INTERRUPTED))
            indexed.filter { it.sessionId == open.id }.forEach { tlogFileDao.updateSize(it.id, File(it.path).length()) }
        }
        val known = indexed.map { it.path }.toSet()
        tlogRecorder.tlogDir.listFiles { f -> f.isFile && f.extension == "tlog" }.orEmpty()
            .filter { it.absolutePath !in known }
            .forEach { file ->
                val end = file.lastModified()
                val start = startOf(file) ?: end
                val sessionId = flightSessionDao.insert(
                    FlightSessionEntity(
                        startTime = start,
                        endTime = end,
                        durationSeconds = ((end - start) / 1000).toInt().coerceAtLeast(0),
                        tlogPath = file.absolutePath,
                        notes = INTERRUPTED,
                    ),
                )
                tlogFileDao.insert(
                    TlogFileEntity(sessionId = sessionId, path = file.absolutePath, kind = LogFileKind.Tlog, sizeBytes = file.length(), createdAtMs = start),
                )
                Log.i(TAG, "Indexed interrupted flight ${file.name}")
            }
    }

    /** The recording start encoded at the front of a tlog file name, or null. */
    private fun startOf(file: File): Long? =
        runCatching { SimpleDateFormat(FILE_STAMP, Locale.US).parse(file.name.take(FILE_STAMP.length))?.time }.getOrNull()

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }
}
