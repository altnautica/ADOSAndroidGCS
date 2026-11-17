package com.altnautica.gcs.feature.cockpit.pilot

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The pilot channel as the pilot's per-instance link-loss watch needs it: every frame carries
 * the app instance's `sid`, a deliberate close says `bye`, and with no RC declared the
 * heartbeat comes from the main looper and only while a stop control is reachable.
 * Each test fails on the base commit, which sent no `sid` or `bye` and ran `hb` from a
 * coroutine whatever the posture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NoRcLinkTest {
    /** One pilot stream: a state frame with [data], keepalives every 500 ms while [alive], and every client frame in [sent]. */
    private class Stream(private val data: String, private val alive: Boolean = true) {
        val sent = mutableListOf<String>()
        var opens = 0

        fun open(outgoing: Flow<String>): Flow<String> = callbackFlow {
            opens++
            val reader = launch { outgoing.collect { sent += it } }
            trySend("""{"kind":"pilot","ts_ms":1,"data":$data}""")
            while (alive) {
                delay(500)
                trySend("""{"kind":"keepalive","ts_ms":1}""")
            }
            awaitClose { reader.cancel() }
        }

        fun ops(): List<String> = sent.map { Json.parseToJsonElement(it).jsonObject.getValue("op").jsonPrimitive.content }

        fun sids(): List<String?> = sent.map { Json.parseToJsonElement(it).jsonObject["sid"]?.jsonPrimitive?.content }
    }

    private fun TestScope.clock() = MonotonicClock { testScheduler.currentTime }

    private fun api(stream: Stream) = object : AutonomyFeedApi {
        private val absent = AgentResult.Err(AgentError.FeatureAbsent)
        override fun detections() = emptyFlow<String>()
        override fun navStream(outbound: Flow<String>) = flow<String> { awaitCancellation() }
        override fun pilotStream(outgoing: Flow<String>) = stream.open(outgoing)
        override suspend fun navStatus(view: String?): AgentResult<JsonObject>? = null
        override suspend fun visionDemand(body: JsonObject) = absent
        override suspend fun visionStatus() = absent
        override suspend fun setDetector(modelId: String) = absent
        override suspend fun mapLive(since: Long) = absent
    }

    @Test
    fun `with no RC and no stop control reachable the channel sends no heartbeat`() = runTest {
        // The cockpit is not resumed and the link notification is not reachable.
        val stream = Stream(NO_RC_AIRBORNE)
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        CockpitPilot(owner, clock(), MutableStateFlow(DeviceId("drone-1")), { api(stream) }, MutableStateFlow<PilotState?>(null), flowOf(null))
        runCurrent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("no hb while no stop control is reachable", emptyList<String>(), stream.ops().filter { it == "hb" })
        owner.cancel()
    }

    @Test
    fun `with no RC and the main looper paused no heartbeat goes out, once it runs one does`() = runTest {
        val stream = Stream(NO_RC_AIRBORNE)
        val main = shadowOf(Looper.getMainLooper())
        main.pause()
        val ch = PilotChannel(backgroundScope, clock()) { stream.open(it) }
        ch.start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("a hung main thread sends no hb", emptyList<String>(), stream.ops().filter { it == "hb" })

        main.idleFor(Duration.ofSeconds(2))
        assertTrue("the main-looper tick sends hb", "hb" in stream.ops())
        ch.stop()
    }

    @Test
    fun `every frame carries the instance's sid, the same across a reconnect`() = runTest {
        var current = Stream(RC_PRESENT, alive = false)
        val streams = mutableListOf<Stream>()
        val ch = PilotChannel(backgroundScope, clock()) { outgoing ->
            streams += current
            current.open(outgoing)
        }
        ch.start()
        runCurrent()
        ch.input(PilotInput(0f, 0f, 0f, 0f, 0))
        ch.takeSession()
        ch.release()
        val action = backgroundScope.launch { ch.action(PilotAction(PilotAction.PAUSE)) }
        runCurrent()
        action.cancel()
        // The first socket goes silent: the link drops at the stale age and reconnects after the backoff.
        current = Stream(RC_PRESENT)
        advanceTimeBy(PilotState.STALE_MS + PilotChannel.RETRY_MS + 1_500)
        runCurrent()

        assertEquals("the channel reconnected", 2, streams.size)
        val first = streams[0]
        val second = streams[1]
        assertTrue("every client op went out: ${first.ops()}", first.ops().containsAll(listOf("hb", "input", "session.take", "release", "action")))
        assertTrue("the new socket hears the heartbeat", "hb" in second.ops())
        val sids = first.sids() + second.sids()
        val sid = sids.first()
        assertTrue("a 32 lowercase hex sid: $sid", sid != null && Regex("[0-9a-f]{32}").matches(sid))
        assertTrue("one sid on every frame of both sockets: $sids", sids.all { it == sid })
        ch.stop()
    }

    @Test
    fun `closing the cockpit's pilot says bye with the same sid before the socket closes`() = runTest {
        val stream = Stream(RC_PRESENT)
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        CockpitPilot(owner, clock(), MutableStateFlow(DeviceId("drone-1")), { api(stream) }, MutableStateFlow<PilotState?>(null), flowOf(null))
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue("the link is up and heard a heartbeat", "hb" in stream.ops())

        owner.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("bye", stream.ops().last())
        assertEquals("one sid, bye included", 1, stream.sids().toSet().size)
    }

    private companion object {
        const val NO_RC_AIRBORNE = """{"owner":"skill","control_mode":"ados","armed":true,"landed":false,"rc_receiver":"absent"}"""
        const val RC_PRESENT = """{"owner":"idle","control_mode":"ados","armed":false,"landed":true}"""
    }
}
