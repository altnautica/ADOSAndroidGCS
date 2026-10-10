package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.command.CloudCommandClient
import com.altnautica.gcs.core.cloud.command.CloudQueuedCommandSink
import com.altnautica.gcs.core.cloud.command.QueuedCommandStatus
import com.altnautica.gcs.core.cloud.grant.GrantState
import com.altnautica.gcs.core.cloud.grant.HeldGrant
import com.altnautica.gcs.core.cloud.node.CloudDroneRow
import com.altnautica.gcs.core.cloud.node.CloudLiveness
import com.altnautica.gcs.core.cloud.node.CloudNodeSource
import com.altnautica.gcs.core.cloud.node.CloudPairing
import com.altnautica.gcs.core.cloud.node.ClaimError
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleCommand
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudNodesAndCommandsTest {

    @Test
    fun livenessThresholds() {
        val now = 100_000L
        assertEquals(Liveness.Live, CloudLiveness.of(now - 44_999, now))
        assertEquals(Liveness.Stale, CloudLiveness.of(now - 45_000, now))
        assertEquals(Liveness.Stale, CloudLiveness.of(now - 59_999, now))
        assertEquals(Liveness.Offline, CloudLiveness.of(now - 60_000, now))
        assertEquals(Liveness.Offline, CloudLiveness.of(null, now))
    }

    @Test
    fun summariesTakeTheFreshestHeartbeatAndGrantCoverage() {
        val statuses = CloudNodeSource.decodeStatuses(
            Json.parseToJsonElement(
                """[{"drone":{"deviceId":"d1","name":"Quad","lastSeen":1000},"status":{"updatedAt":90000,"profile":"drone","fcVariant":"ardupilot","extra":1}},
                   {"drone":{"deviceId":"g1","name":"Ground"},"status":null}]""",
            ),
        )
        val drones = listOf(CloudDroneRow("d1", "Quad"), CloudDroneRow("g1", "Ground", profile = "ground-station"))
        val grant = GrantState(grant = HeldGrant("p", "s", listOf("d1", "g1"), 1_000_000, true))
        val nodes = CloudNodeSource.summarize(drones, statuses, grant, nowMs = 100_000)

        val d1 = nodes.first { it.id == DeviceId("d1") }
        assertEquals(Liveness.Live, d1.liveness)
        assertEquals(listOf(Reach.CloudLive, Reach.CloudQueue), d1.reaches)
        assertEquals(NodeProfile.Drone, d1.profile)
        assertEquals(FcVariant.MavlinkArdupilot, d1.fcVariant)
        assertEquals(90_000L, d1.lastSeenMs)

        val g1 = nodes.first { it.id == DeviceId("g1") }
        assertEquals(Liveness.Offline, g1.liveness)
        assertEquals(NodeProfile.GroundStation, g1.profile)
        assertEquals(listOf(Reach.CloudQueue), g1.reaches)

        val noGrant = CloudNodeSource.summarize(drones, statuses, GrantState(), 100_000)
        assertEquals(listOf(Reach.CloudQueue), noGrant.first().reaches)
    }

    @Test
    fun queuedCommandIsQueuedWithTheReceiptId() = runTest {
        val gateway = FakeGateway().apply { onMutation = { _, _ -> buildJsonObject { put("commandId", "row-42") } } }
        val sink = CloudQueuedCommandSink(CloudCommandClient(gateway, FakeAuth()))
        assertTrue(sink.supports(Reach.CloudQueue))
        assertTrue(!sink.supports(Reach.CloudLive))

        val result = sink.run(DeviceId("d1"), VehicleCommand.Takeoff(12f))
        assertEquals(CommandResult.Queued("row-42"), result)
        val call = gateway.calls.single()
        assertEquals("cmdDroneCommands:enqueueCommand", call.name)
        assertEquals("d1", call.args["deviceId"])
        assertEquals("send_command", call.args["command"])
        assertEquals(mapOf("cmd" to "takeoff", "args" to listOf(12.0)), call.args["args"])
        assertEquals(10_000.0, call.args["ttlMs"])
    }

    @Test
    fun verbsMatchTheQueueVocabulary() {
        assertEquals("mode" to listOf<Any?>("LOITER"), CloudCommandClient.verbOf(VehicleCommand.SetMode(FlightMode(5, "LOITER"))))
        assertEquals("killswitch", CloudCommandClient.verbOf(VehicleCommand.Kill)?.first)
        assertEquals("pausemission", CloudCommandClient.verbOf(VehicleCommand.PauseMission)?.first)
        assertEquals("resumemission", CloudCommandClient.verbOf(VehicleCommand.ResumeMission)?.first)
        assertEquals("rtl", CloudCommandClient.verbOf(VehicleCommand.ReturnToLaunch)?.first)
        assertEquals(null, CloudCommandClient.verbOf(VehicleCommand.FlyTo(0.0, 0.0, 10f)))
    }

    @Test
    fun signedOutIsDeniedWithoutEnqueueing() = runTest {
        val gateway = FakeGateway()
        val result = CloudCommandClient(gateway, FakeAuth(signedIn = false)).run(DeviceId("d1"), VehicleCommand.Land)
        assertTrue(result is CommandResult.Denied)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun commandStatusParses() {
        fun p(s: String) = CloudCommandClient.parseStatus(Json.parseToJsonElement(s))
        assertEquals(QueuedCommandStatus.Pending, p("""{"status":"pending"}"""))
        assertEquals(QueuedCommandStatus.Completed("ok"), p("""{"status":"completed","result":{"success":true,"message":"ok"}}"""))
        assertEquals(QueuedCommandStatus.Failed("no"), p("""{"status":"completed","result":{"success":false,"message":"no"}}"""))
        assertEquals(QueuedCommandStatus.Unknown, p("null"))
    }

    @Test
    fun claimErrorsMap() {
        assertEquals(ClaimError.InvalidCode, CloudPairing.errorOf("invalid_pairing_code"))
        assertEquals(ClaimError.Expired, CloudPairing.errorOf("pairing_code_expired"))
        assertEquals(ClaimError.AlreadyClaimed, CloudPairing.errorOf("code_already_claimed"))
        assertEquals(ClaimError.OwnedByOther, CloudPairing.errorOf("device_owned_by_other"))
        assertEquals(ClaimError.RateLimited, CloudPairing.errorOf("rate_limited"))
    }
}
