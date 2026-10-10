package com.altnautica.gcs.core.link.testing

import com.altnautica.gcs.core.link.demo.DemoOptions
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.TransportMavlinkLink
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.transport.DemoTransport
import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope

/** A link to a fresh simulated ArduCopter, running on the test scheduler's virtual time. */
class DemoHarness(val link: TransportMavlinkLink, val transport: DemoTransport)

suspend fun TestScope.demoHarness(options: DemoOptions = DemoOptions()): DemoHarness {
    val transport = DemoTransport(backgroundScope.coroutineContext, options)
    val link = TransportMavlinkLink(backgroundScope, ElapsedClock { testScheduler.currentTime })
    link.attach(transport)
    link.target.filterNotNull().first()
    return DemoHarness(link, transport)
}

/**
 * Scripted vehicle: every message the GCS sends is recorded and handed to
 * [respond], whose replies are delivered as frames from the target.
 */
class ScriptedLink(
    target: VehicleTarget = VehicleTarget(1, 1, 3, 2),
    private val respond: suspend ScriptedLink.(MavMessage) -> Unit = {},
) : MavlinkLink {
    private val vehicleWriter = MavlinkFrameWriter(sysId = target.sysId, compId = target.compId)
    private val _frames = MutableSharedFlow<MavlinkFrame>(extraBufferCapacity = 64)
    override val frames: SharedFlow<MavlinkFrame> = _frames
    override val target: StateFlow<VehicleTarget?> = MutableStateFlow(target)

    val sent = mutableListOf<MavMessage>()

    override suspend fun send(message: MavMessage): WriteResult {
        sent += message
        respond(message)
        return WriteResult.Sent
    }

    suspend fun reply(message: MavMessage) {
        val frame = MavlinkFrameParser().parse(vehicleWriter.encode(message)).single()
        _frames.emit(frame)
    }
}
