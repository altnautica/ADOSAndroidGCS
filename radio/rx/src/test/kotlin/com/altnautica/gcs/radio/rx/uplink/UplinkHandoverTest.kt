package com.altnautica.gcs.radio.rx.uplink

import com.altnautica.gcs.radio.rx.session.GroundBeacon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class UplinkHandoverTest {

    private val key = ByteArray(32) { (0x80 + it).toByte() }
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A call named here waits for its gate; it is recorded in [calls] on entry. */
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private fun gate(call: String) = CompletableDeferred<Unit>().also { gates[call] = it }

    private suspend fun pass(call: String) {
        calls += call
        gates[call]?.await()
    }

    private val radio = object : HandoverRadio {
        override val receiving = MutableStateFlow(true)
        override val holding = MutableStateFlow(false)
        val beacons = MutableSharedFlow<GroundBeacon>(extraBufferCapacity = 8)
        override val groundBeacons = beacons
        var holdOk = true
        var holdFails: RuntimeException? = null
        var quiet = true

        override suspend fun hold(): Boolean {
            pass("hold")
            holdFails?.let { throw it }
            if (holdOk) holding.value = true
            return holdOk
        }

        override suspend fun release(): Boolean {
            pass("release")
            holding.value = false
            return quiet
        }
    }

    private var box: BoxAccess? = BoxAccess(
        deviceId = BOX,
        name = "Field box",
        target = BoxTarget("http://192.0.2.10:8080/api/v1/ground-station/uplink".toHttpUrl(), "node-key"),
        auth = PhoneAuth(ByteArray(32) { 7 }, key),
    )

    /** Reads of the stored invite, each a vault decrypt in the app. */
    private val loads = AtomicInteger()

    private val boxes = object : BoxDirectory {
        override fun boxId(): String? = box?.deviceId
        override suspend fun load(): BoxAccess? = box.also { loads.incrementAndGet() }
    }

    private var takeReply: BoxReply = BoxReply.Ok("standby")
    private var giveBackReply: BoxReply = BoxReply.Ok("transmitting")
    private val sent: MutableList<SignedRequest> = Collections.synchronizedList(mutableListOf())

    private val api = object : BoxUplinkApi {
        override suspend fun post(target: BoxTarget, request: SignedRequest): BoxReply {
            sent += request
            val op = if (request.body.decodeToString().contains("\"take\"")) "take" else "give_back"
            pass("post:$op")
            return if (op == "take") takeReply else giveBackReply
        }

        override suspend fun status(target: BoxTarget): BoxStatus = BoxStatus.Reached(null)
    }

    private val clock = object : HandoverClock {
        var wall = NOW
        var elapsed = 100_000L
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
    }

    private val handover = UplinkHandover(
        radio = radio,
        boxes = boxes,
        api = api,
        holderId = { PHONE },
        holderName = { "Ops phone" },
        scope = scope,
        clock = clock,
        yieldPort = 0,
        log = { logs += it },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aTakeStartsTheTransmittersOnlyAfterTheBoxIsInStandby() = runBlocking {
        val s = handover.take()
        assertEquals(HandoverPhase.Transmitting, s.phase)
        assertEquals(HoldSource.Taken, s.source)
        assertEquals(listOf("post:take", "hold"), calls)
        val body = sent.single().body.decodeToString()
        assertTrue(body, body.contains("\"holder_device_id\":\"$PHONE\""))
        assertTrue(body, body.contains("\"yield_port\":${handover.yieldListenerPort}"))
        assertTrue(logs.toString(), logs.contains("uplink state receive_only -> taking reason=take requested from $BOX"))
        assertTrue(logs.toString(), logs.contains("uplink state taking -> transmitting reason=box $BOX in standby"))
    }

    @Test
    fun refusalsLeaveThePhoneSilentWithOneSentence() = runBlocking {
        radio.receiving.value = false
        assertEquals(HandoverNote.NoRadio, handover.take().note)
        radio.receiving.value = true

        takeReply = BoxReply.Refused("E_BIND_ACTIVE", "A bind session is running on this ground station.")
        handover.take().let {
            assertEquals(HandoverPhase.ReceiveOnly, it.phase)
            assertEquals(HandoverNote.BoxRefused("A bind session is running on this ground station."), it.note)
        }
        takeReply = BoxReply.Unreachable
        assertEquals(HandoverNote.NoLanReach("Field box"), handover.take().note)

        box = BoxAccess(BOX, "Field box", target = null, auth = PhoneAuth(ByteArray(32), key))
        assertEquals(HandoverNote.NoLanReach("Field box"), handover.take().note)
        box = BoxAccess(BOX, "Field box", target = null, auth = null)
        assertEquals(HandoverNote.NoHandoverKey, handover.take().note)
        box = null
        assertEquals(HandoverNote.NoHandoverKey, handover.take().note)
        assertTrue(calls.toString(), "hold" !in calls)
        assertNull(handover.yieldListenerPort)
    }

    @Test
    fun anotherGroundHeardBeforeTakingRefusesTheTake() = runBlocking {
        awaitTrue { radio.beacons.subscriptionCount.value > 0 }
        radio.beacons.emit(GroundBeacon("other-ground", 36))
        delay(200)
        assertEquals(HandoverNote.OtherGround("other-ground"), handover.take().note)
        assertTrue(calls.toString(), calls.isEmpty())
    }

    @Test
    fun transmittersThatDoNotOpenHandTheUplinkStraightBack() = runBlocking {
        radio.holdOk = false
        val s = handover.take()
        assertEquals(HandoverPhase.ReceiveOnly, s.phase)
        assertEquals(HandoverNote.TxDidNotOpen("Field box"), s.note)
        assertEquals(listOf("post:take", "hold", "post:give_back"), calls)
    }

    @Test
    fun giveBackStopsFirstThenTellsTheBox() = runBlocking {
        handover.take()
        calls.clear()
        val s = handover.giveBack()
        assertEquals(HandoverPhase.Yielded, s.phase)
        assertEquals(HandoverNote.GaveBack("Field box"), s.note)
        assertEquals(listOf("release", "post:give_back"), calls)
        assertNull(handover.yieldListenerPort)
    }

    @Test
    fun aGiveBackThatDoesNotGoQuietNeverTellsTheBox() = runBlocking {
        handover.take()
        calls.clear()
        radio.quiet = false
        assertEquals(HandoverNote.TxNotQuiet("Field box"), handover.giveBack().note)
        assertEquals(listOf("release"), calls)
    }

    @Test
    fun tieRuleThePhoneYieldsToAnotherGroundAtOnce() = runBlocking {
        handover.take()
        // The box's last beacon inside the grace window is not a tie.
        radio.beacons.emit(GroundBeacon(BOX, 36))
        delay(200)
        assertEquals(HandoverPhase.Transmitting, handover.state.value.phase)
        radio.beacons.emit(GroundBeacon("other-ground", 36))
        awaitTrue { handover.state.value.phase == HandoverPhase.Yielded }
        assertEquals(HandoverNote.OtherGround("other-ground"), handover.state.value.note)
        assertTrue(calls.toString(), "release" in calls)
        assertTrue(logs.toString(), logs.contains("uplink state transmitting -> yielded reason=Another ground is transmitting: other-ground"))
    }

    @Test
    fun theBoxTransmittingAfterTheGraceIsATieToo() = runBlocking {
        handover.take()
        clock.elapsed += UplinkHandover.TAKE_GRACE_MS + 1
        radio.beacons.emit(GroundBeacon(BOX, 36))
        awaitTrue { handover.state.value.note == HandoverNote.OtherGround(BOX) }
    }

    @Test
    fun aSignedTakeBackStopsThePhoneAndIsAnswered() = runBlocking {
        handover.take()
        val port = requireNotNull(handover.yieldListenerPort)
        assertEquals(200, yieldCall(port, NOW))
        assertEquals(HandoverPhase.Yielded, handover.state.value.phase)
        assertEquals(HandoverNote.TakenBack("Field box"), handover.state.value.note)
        assertTrue(logs.toString(), logs.contains("uplink yield received from=$BOX verdict=ok"))
    }

    @Test
    fun theTakeBackEndpointRejectsBadStaleAndReplayedSignatures() = runBlocking {
        handover.take()
        val port = requireNotNull(handover.yieldListenerPort)
        assertEquals(401, yieldCall(port, NOW, signWith = ByteArray(32)))
        assertEquals(401, yieldCall(port, NOW - 121_000))
        assertEquals(401, yieldCall(port, NOW, withMac = false))
        assertEquals(HandoverPhase.Transmitting, handover.state.value.phase)
        assertEquals(404, yieldCall(port, NOW, path = "/other"))
        assertEquals(200, yieldCall(port, NOW))
        assertEquals(HandoverPhase.Yielded, handover.state.value.phase)
        // The listener closed with the yield; a fresh one would still refuse the replay.
        handover.take()
        val again = requireNotNull(handover.yieldListenerPort)
        assertEquals(401, yieldCall(again, NOW))
        assertTrue(logs.toString(), logs.contains("uplink yield received from=$BOX verdict=replay"))
        assertTrue(logs.toString(), logs.contains("uplink yield received from=$BOX verdict=bad_mac"))
        assertTrue(logs.toString(), logs.contains("uplink yield received from=$BOX verdict=stale"))
    }

    @Test
    fun losingTheRadioWhileTransmittingYields() = runBlocking {
        handover.take()
        radio.holding.value = false
        awaitTrue { handover.state.value.phase == HandoverPhase.Yielded }
        assertEquals(HandoverNote.RadioStopped, handover.state.value.note)
    }

    @Test
    fun aRunThatHoldsFromItsStartIsTheBenchOrTheOwnFleet() = runBlocking {
        radio.holding.value = true
        awaitTrue { handover.state.value.source == HoldSource.Bench }
        // The bench holds regardless of other grounds: no tie there.
        radio.beacons.emit(GroundBeacon("other-ground", 36))
        delay(200)
        assertEquals(HandoverPhase.Transmitting, handover.state.value.phase)
        radio.holding.value = false
        awaitTrue { handover.state.value.phase == HandoverPhase.ReceiveOnly }

        box = null
        radio.holding.value = true
        awaitTrue { handover.state.value.source == HoldSource.OwnFleet }
        radio.beacons.emit(GroundBeacon("other-ground", 36))
        awaitTrue { handover.state.value.note == HandoverNote.OtherGround("other-ground") }
    }

    @Test
    fun thePhonesOwnSignedTakeIsNotATakeBack() = runBlocking {
        handover.take()
        val port = requireNotNull(handover.yieldListenerPort)
        // Captured on the LAN and played straight to the phone's endpoint.
        val take = sent.single()
        assertEquals(401, post(port, take.headers, take.body))
        assertEquals(HandoverPhase.Transmitting, handover.state.value.phase)
        assertTrue(logs.toString(), logs.contains("uplink yield received from=unknown verdict=bad_mac"))
    }

    @Test
    fun aTakeBackCarryingAnotherOpOrNamingAnotherBoxIsRefused() = runBlocking {
        handover.take()
        val port = requireNotNull(handover.yieldListenerPort)
        assertEquals(401, yieldCall(port, NOW, body = """{"box_device_id":"$BOX","op":"take"}"""))
        assertEquals(401, yieldCall(port, NOW + 1, body = """{"box_device_id":"gs-other","op":"yield"}"""))
        assertEquals(HandoverPhase.Transmitting, handover.state.value.phase)
        assertTrue(logs.toString(), logs.contains("uplink yield received from=$BOX verdict=not_yield"))
        assertTrue(logs.toString(), logs.contains("uplink yield received from=gs-other verdict=not_yield"))
        assertEquals(200, yieldCall(port, NOW + 2))
    }

    @Test
    fun aTakeBackNeverOpensTheVault() = runBlocking {
        handover.take()
        val port = requireNotNull(handover.yieldListenerPort)
        val afterTake = loads.get()
        assertEquals(401, yieldCall(port, NOW, signWith = ByteArray(32)))
        assertEquals(200, yieldCall(port, NOW))
        assertEquals(afterTake, loads.get())
    }

    @Test
    fun aTakeWhoseCallerGoesAwayStillSettles() = runBlocking {
        val answer = gate("post:take")
        val caller = launch(Dispatchers.IO) { handover.take() }
        awaitTrue { "post:take" in calls }
        caller.cancelAndJoin()
        answer.complete(Unit)
        awaitTrue { handover.state.value.let { it.phase == HandoverPhase.Transmitting && !it.busy } }
        assertEquals(listOf("post:take", "hold"), calls.toList())
        assertEquals(HandoverNote.GaveBack("Field box"), handover.giveBack().note)
    }

    @Test
    fun aTakeCancelledWhileTheTransmittersOpenStillSettles() = runBlocking {
        awaitTrue { radio.beacons.subscriptionCount.value > 0 }
        val opened = gate("hold")
        val caller = launch(Dispatchers.IO) { handover.take() }
        awaitTrue { "hold" in calls }
        caller.cancelAndJoin()
        opened.complete(Unit)
        awaitTrue { handover.state.value.let { it.phase == HandoverPhase.Transmitting && !it.busy } }
        // Not stuck: the tie rule still stops the phone.
        clock.elapsed += UplinkHandover.TAKE_GRACE_MS + 1
        radio.beacons.emit(GroundBeacon("other-ground", 36))
        awaitTrue { handover.state.value.note == HandoverNote.OtherGround("other-ground") }
        assertFalse(radio.holding.value)
    }

    @Test
    fun aGiveBackWhoseCallerGoesAwayStillTellsTheBox() = runBlocking {
        handover.take()
        calls.clear()
        val stopped = gate("release")
        val caller = launch(Dispatchers.IO) { handover.giveBack() }
        awaitTrue { "release" in calls }
        caller.cancelAndJoin()
        stopped.complete(Unit)
        awaitTrue { handover.state.value.phase == HandoverPhase.Yielded }
        assertEquals(HandoverState(HandoverPhase.Yielded, note = HandoverNote.GaveBack("Field box")), handover.state.value)
        assertEquals(listOf("release", "post:give_back"), calls.toList())
    }

    @Test
    fun aTakeThatFailsAfterTheBoxStoodByHandsTheUplinkBack() = runBlocking {
        radio.holdFails = IllegalStateException("transmit pipe gone")
        val failure = runCatching { handover.take() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalStateException)
        assertEquals(HandoverState(HandoverPhase.ReceiveOnly, note = HandoverNote.TxDidNotOpen("Field box")), handover.state.value)
        assertEquals(listOf("post:take", "hold", "release", "post:give_back"), calls.toList())
        assertNull(handover.yieldListenerPort)
    }

    @Test
    fun aTakeCutShortByTheHandoverItselfHandsTheUplinkBack() = runBlocking {
        gate("post:take")
        launch(Dispatchers.IO) { runCatching { handover.take() } }
        awaitTrue { "post:take" in calls }
        scope.cancel()
        awaitTrue { handover.state.value.phase == HandoverPhase.ReceiveOnly }
        assertEquals(HandoverNote.TxDidNotOpen("Field box"), handover.state.value.note)
        assertEquals(listOf("post:take", "post:give_back"), calls.toList())
    }

    /** One take-back call as the box makes it; the HTTP status the phone answered. */
    private fun yieldCall(
        port: Int,
        ts: Long,
        signWith: ByteArray = key,
        withMac: Boolean = true,
        path: String = "/uplink/yield",
        body: String = """{"box_device_id":"$BOX","op":"yield"}""",
    ): Int {
        val bytes = body.encodeToByteArray()
        val headers = buildMap {
            put("x-ados-uplink-ts", ts.toString())
            if (withMac) put("x-ados-uplink-mac", UplinkMac.macBase64(UplinkMac.Domain.Yield, signWith, ts, bytes))
        }
        return post(port, headers, bytes, path)
    }

    /** One raw `POST` to the phone's take-back endpoint; the HTTP status it answered. */
    private fun post(port: Int, headers: Map<String, String>, body: ByteArray, path: String = "/uplink/yield"): Int {
        val head = buildString {
            append("POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        }
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write(head.encodeToByteArray() + body)
            val line = s.getInputStream().bufferedReader().readLine()
            return line.split(' ')[1].toInt()
        }
    }

    private suspend fun awaitTrue(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(20)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val BOX = "gs-box-1"
        const val PHONE = "a1b2c3d4e5f6"
    }
}
