package com.altnautica.gcs.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedPlanDaoTest {
    private lateinit var db: AdosDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AdosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun plan(id: String, updated: Long, name: String = id) =
        SavedPlanEntity(id = id, name = name, createdAtMs = 1, updatedAtMs = updated, itemCount = 3, planJson = "{}")

    @Test
    fun upsertReplacesAndObserveAllOrdersByUpdate() = runTest {
        val dao = db.savedPlanDao()
        dao.observeAll().test {
            assertEquals(emptyList<SavedPlanEntity>(), awaitItem())
            dao.upsert(plan("a", 10))
            assertEquals(listOf("a"), awaitItem().map { it.id })
            dao.upsert(plan("b", 20))
            assertEquals(listOf("b", "a"), awaitItem().map { it.id })
            dao.upsert(plan("a", 30, name = "renamed"))
            val list = awaitItem()
            assertEquals(listOf("a", "b"), list.map { it.id })
            assertEquals("renamed", list.first().name)
            dao.delete("a")
            assertEquals(listOf("b"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(dao.getById("a"))
    }

    @Test
    fun deletingSessionDetachesItsLogFiles() = runTest {
        val sessionId = db.flightSessionDao().insert(FlightSessionEntity(startTime = 100))
        val logs = db.tlogFileDao()
        val fileId = logs.insert(
            TlogFileEntity(sessionId = sessionId, path = "/f/1.tlog", kind = LogFileKind.Tlog, sizeBytes = 5, createdAtMs = 100),
        )
        db.flightSessionDao().delete(db.flightSessionDao().getById(sessionId)!!)
        val file = logs.getById(fileId)!!
        assertNull(file.sessionId)
        assertEquals(LogFileKind.Tlog, file.kind)
    }
}
