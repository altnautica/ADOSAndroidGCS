package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import com.altnautica.gcs.feature.cockpit.testSpec
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The light bar is driven from its own dispatcher, never from the scope's (main) thread. */
class ControllerFeedbackTest {
    @Test
    fun `the light bar is written on the light bar dispatcher`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, LIGHTBAR_THREAD) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val threads = mutableListOf<String>()
        val written = CountDownLatch(1)
        val controllers = mockk<ControllerManager>(relaxed = true) {
            every { active } returns MutableStateFlow<ControllerInfo?>(null)
            every { setLightbar(any(), any()) } answers {
                synchronized(threads) { threads += Thread.currentThread().name }
                written.countDown()
            }
        }
        try {
            val feedback = ControllerFeedback(
                scope,
                controllers,
                MutableStateFlow(emptyList<CockpitAlert>()),
                MutableStateFlow(ControllerContext()),
                testSpec.ds4.lightbar,
                testSpec.ds4.rumble,
                executor.asCoroutineDispatcher(),
            )
            feedback.start()
            assertTrue("no light bar frame", written.await(5, TimeUnit.SECONDS))
            // The coroutine debug agent appends " @coroutine#N" to the thread's name.
            assertEquals(LIGHTBAR_THREAD, synchronized(threads) { threads.first() }.substringBefore(" @"))
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val LIGHTBAR_THREAD = "lightbar-test"
    }
}
