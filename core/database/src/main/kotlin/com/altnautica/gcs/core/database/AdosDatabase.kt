package com.altnautica.gcs.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FlightSessionEntity::class, TlogFileEntity::class, SavedPlanEntity::class],
    version = AdosDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(AdosConverters::class)
abstract class AdosDatabase : RoomDatabase() {
    abstract fun flightSessionDao(): FlightSessionDao
    abstract fun tlogFileDao(): TlogFileDao
    abstract fun savedPlanDao(): SavedPlanDao

    companion object {
        const val VERSION = 2

        /**
         * File name of the database. Version 1 shipped under this name holding only
         * `flight_sessions`; keeping it lets [MIGRATION_1_2] carry existing flight history over.
         */
        const val NAME = "ados_flights.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tlog_files` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER, " +
                        "`path` TEXT NOT NULL, `kind` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `deviceId` TEXT, `fcLogId` INTEGER, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `flight_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tlog_files_sessionId` ON `tlog_files` (`sessionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tlog_files_path` ON `tlog_files` (`path`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_plans` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, `vehicleType` TEXT, `itemCount` INTEGER NOT NULL, " +
                        "`homeLatDeg` REAL, `homeLonDeg` REAL, `planJson` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_plans_updatedAtMs` ON `saved_plans` (`updatedAtMs`)")
                // Index the telemetry logs that version 1 referenced only by path.
                db.execSQL(
                    "INSERT OR IGNORE INTO `tlog_files` (`sessionId`, `path`, `kind`, `sizeBytes`, `createdAtMs`) " +
                        "SELECT `id`, `tlogPath`, 'Tlog', 0, `startTime` FROM `flight_sessions` WHERE `tlogPath` != ''",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}

class AdosConverters {
    @TypeConverter
    fun logFileKindToString(kind: LogFileKind): String = kind.name

    @TypeConverter
    fun stringToLogFileKind(value: String): LogFileKind = LogFileKind.valueOf(value)
}
