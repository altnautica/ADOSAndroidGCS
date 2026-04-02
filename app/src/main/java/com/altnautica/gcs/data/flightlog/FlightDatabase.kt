package com.altnautica.gcs.data.flightlog

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "flight_sessions")
data class FlightSession(
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

@Dao
interface FlightSessionDao {
    @Insert
    suspend fun insert(session: FlightSession): Long

    @Query("SELECT * FROM flight_sessions ORDER BY startTime DESC")
    fun getAll(): Flow<List<FlightSession>>

    @Query("SELECT * FROM flight_sessions WHERE id = :id")
    suspend fun getById(id: Long): FlightSession?

    @Delete
    suspend fun delete(session: FlightSession)

    @Update
    suspend fun update(session: FlightSession)
}

@Database(entities = [FlightSession::class], version = 1, exportSchema = false)
abstract class FlightDatabase : RoomDatabase() {
    abstract fun flightSessionDao(): FlightSessionDao
}
