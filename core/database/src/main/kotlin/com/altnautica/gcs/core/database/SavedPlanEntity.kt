package com.altnautica.gcs.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mission plan kept in the on-device plan library.
 *
 * [planJson] holds the whole plan (mission items, fence, rally) in the QGroundControl
 * `.plan` JSON format so a saved plan can be exported unchanged. [itemCount] and the
 * home fields are denormalised so the library list renders without parsing every plan.
 * Times are wall-clock epoch milliseconds.
 */
@Entity(tableName = "saved_plans", indices = [Index(value = ["updatedAtMs"])])
data class SavedPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val vehicleType: String? = null,
    val itemCount: Int = 0,
    val homeLatDeg: Double? = null,
    val homeLonDeg: Double? = null,
    val planJson: String,
)
