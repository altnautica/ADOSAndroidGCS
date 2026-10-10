package com.altnautica.gcs.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Builds the version 1 database exactly as the earlier app release wrote it, then opens it
 * through Room with the shipped migrations. Room validates every migrated table against the
 * current entities, so a schema mismatch fails the open.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun migratesVersionOneFlightHistory() = runTest {
        val file = context.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `flight_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
                    "`maxAltitude` REAL NOT NULL, `maxSpeed` REAL NOT NULL, `maxDistance` REAL NOT NULL, " +
                    "`totalDistance` REAL NOT NULL, `batteryStart` INTEGER NOT NULL, `batteryEnd` INTEGER NOT NULL, " +
                    "`tlogPath` TEXT NOT NULL, `connectionMode` TEXT NOT NULL, `vehicleType` TEXT NOT NULL, `notes` TEXT NOT NULL)",
            )
            v1.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            v1.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'v1')")
            v1.execSQL(
                "INSERT INTO flight_sessions VALUES (1, 1000, 61000, 60, 42.5, 12.0, 300.0, 900.0, 98, 61, " +
                    "'/logs/a.tlog', 'USB', 'Copter', 'first')",
            )
            v1.execSQL(
                "INSERT INTO flight_sessions VALUES (2, 2000, 3000, 1, 1.0, 1.0, 1.0, 1.0, -1, -1, '', 'UDP', 'Plane', '')",
            )
            v1.version = 1
        }

        val db = Room.databaseBuilder(context, AdosDatabase::class.java, file.absolutePath)
            .addMigrations(*AdosDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val sessions = db.flightSessionDao().getAll().first()
            assertEquals(listOf(2L, 1L), sessions.map { it.id })
            assertEquals(42.5f, sessions.last().maxAltitude)
            assertEquals("first", sessions.last().notes)

            val logs = db.tlogFileDao().observeAll().first()
            assertEquals(1, logs.size)
            assertEquals(1L, logs.single().sessionId)
            assertEquals("/logs/a.tlog", logs.single().path)
            assertEquals(LogFileKind.Tlog, logs.single().kind)

            assertEquals(emptyList<SavedPlanEntity>(), db.savedPlanDao().observeAll().first())
        } finally {
            db.close()
            file.delete()
        }
    }
}
