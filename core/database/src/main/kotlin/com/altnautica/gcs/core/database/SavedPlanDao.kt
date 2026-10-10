package com.altnautica.gcs.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlanDao {
    /** Inserts the plan, or replaces the row with the same [SavedPlanEntity.id]. */
    @Upsert
    suspend fun upsert(plan: SavedPlanEntity)

    /** Every saved plan, most recently updated first. */
    @Query("SELECT * FROM saved_plans ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<SavedPlanEntity>>

    @Query("SELECT * FROM saved_plans WHERE id = :id")
    suspend fun getById(id: String): SavedPlanEntity?

    @Query("DELETE FROM saved_plans WHERE id = :id")
    suspend fun delete(id: String)
}
