package com.altnautica.gcs.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One armed-to-disarmed flight. Times are wall-clock epoch milliseconds,
 * distances and altitudes metres, speeds metres per second. Battery fields
 * hold the remaining percentage, or -1 when the vehicle did not report it.
 */
@Entity(tableName = "flight_sessions")
data class FlightSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val durationSeconds: Int = 0,
    val maxAltitude: Float = 0f,
    val maxSpeed: Float = 0f,
    val maxDistance: Float = 0f,
    val totalDistance: Float = 0f,
    val batteryStart: Int = -1,
    val batteryEnd: Int = -1,
    val tlogPath: String = "",
    val connectionMode: String = "",
    val vehicleType: String = "",
    val notes: String = "",
)
