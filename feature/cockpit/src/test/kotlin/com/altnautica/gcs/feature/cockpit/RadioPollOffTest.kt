package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.feature.cockpit.hud.layers.Freshness
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Over a link that cannot carry the navigation socket the cockpit lives on the status
 * poll. When that poll stops answering, what it last brought ages out: the cockpit
 * reads stale, never fresh, and stops claiming the reduced-rate feed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioPollOffTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `with no navigation socket and the status poll gone quiet, the cockpit reads stale`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true)
        val obstacles = javaClass.classLoader!!.getResource("fixtures/obstacles-frame.json")!!.readText()
        val agent = FakeAutonomyAgent(this, """{"owner":"goto","armed":true}""", radio = true, statusBody = {
            val t = testScheduler.currentTime
            """{"obstacles":{"ts_ms":$t,"age_ms":100,"data":$obstacles},""" +
                """"pilot":{"ts_ms":$t,"age_ms":100,"data":{"owner":"goto","armed":true}}}"""
        })
        val vm = testCockpitVm(vehicle, agent.apis)
        val store = vm.autonomy.layers.store
        fun obstaclesFreshness() = LayerStore.freshness(store.obstacles.ageMs(testScheduler.currentTime))

        // The poll answers: its frames drive the cockpit, marked as reduced rate.
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue("the status route is polled", agent.polls.isNotEmpty())
        assertTrue(vm.autonomy.feed.reducedRate.value)
        assertEquals("goto", vm.chip.value.ruleId)
        assertFalse(vm.chip.value.dim)
        assertEquals(Freshness.Fresh, obstaclesFreshness())

        // The poll stops answering.
        agent.statusAnswers = false
        val asked = agent.polls.size
        advanceTimeBy(500)
        runCurrent()
        assertTrue("the feed keeps asking", agent.polls.size > asked)
        assertFalse("nothing arrives, so no reduced-rate feed is claimed", vm.autonomy.feed.reducedRate.value)

        // Past the chip's 2 s limit and the layers' 2 s hide: stale, not fresh.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("the chip reads the link as stale", "link_stale", vm.chip.value.ruleId)
        assertTrue("a stale chip is dimmed", vm.chip.value.dim)
        assertEquals("the last polled obstacles are hidden", Freshness.Hidden, obstaclesFreshness())
        vm.viewModelScope.cancel()
    }
}
