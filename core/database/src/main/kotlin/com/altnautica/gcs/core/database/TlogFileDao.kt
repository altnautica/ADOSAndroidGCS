package com.altnautica.gcs.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TlogFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: TlogFileEntity): Long

    @Query("SELECT * FROM tlog_files ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<TlogFileEntity>>

    @Query("SELECT * FROM tlog_files WHERE sessionId = :sessionId ORDER BY createdAtMs")
    fun observeForSession(sessionId: Long): Flow<List<TlogFileEntity>>

    @Query("SELECT * FROM tlog_files WHERE sessionId = :sessionId ORDER BY createdAtMs")
    suspend fun listForSession(sessionId: Long): List<TlogFileEntity>

    @Query("SELECT * FROM tlog_files WHERE id = :id")
    suspend fun getById(id: Long): TlogFileEntity?

    @Query("UPDATE tlog_files SET sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun updateSize(id: Long, sizeBytes: Long)

    @Query("DELETE FROM tlog_files WHERE id = :id")
    suspend fun delete(id: Long)
}
