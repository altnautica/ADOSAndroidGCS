package com.altnautica.gcs.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FlightSessionDao {
    @Insert
    suspend fun insert(session: FlightSessionEntity): Long

    @Query("SELECT * FROM flight_sessions ORDER BY startTime DESC")
    fun getAll(): Flow<List<FlightSessionEntity>>

    @Query("SELECT * FROM flight_sessions WHERE id = :id")
    suspend fun getById(id: Long): FlightSessionEntity?

    @Delete
    suspend fun delete(session: FlightSessionEntity)

    @Update
    suspend fun update(session: FlightSessionEntity)
}
