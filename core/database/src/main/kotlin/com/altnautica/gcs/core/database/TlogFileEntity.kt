package com.altnautica.gcs.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What produced a log file on the device. */
enum class LogFileKind {
    /** MAVLink telemetry log recorded by the app while a link was open. */
    Tlog,

    /** Flight-controller dataflash log downloaded over MAVLink. */
    Dataflash,
}

/**
 * Index row for a log file kept in app-specific storage. [sessionId] links a telemetry log
 * to the flight it covers; it is null for dataflash downloads and orphaned logs.
 */
@Entity(
    tableName = "tlog_files",
    foreignKeys = [
        ForeignKey(
            entity = FlightSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["path"], unique = true)],
)
data class TlogFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val path: String,
    val kind: LogFileKind,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val deviceId: String? = null,
    val fcLogId: Int? = null,
)
