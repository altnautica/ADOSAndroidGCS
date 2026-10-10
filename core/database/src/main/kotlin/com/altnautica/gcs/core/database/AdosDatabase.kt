package com.altnautica.gcs.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [FlightSessionEntity::class], version = 1, exportSchema = true)
abstract class AdosDatabase : RoomDatabase() {
    abstract fun flightSessionDao(): FlightSessionDao
}
