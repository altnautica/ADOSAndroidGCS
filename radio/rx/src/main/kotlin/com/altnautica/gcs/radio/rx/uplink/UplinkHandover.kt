package com.altnautica.gcs.radio.rx.uplink

import android.util.Log
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.session.GroundBeacon
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.tx.PhoneGroundId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's side of the uplink handover with the ground station its invite
 * came from: one ground transmits for a fleet at a time.
 *
 * - Take: a signed `take` to the box's LAN route; the transmitters start only
 *   once the box answers `{ok, state: "standby"}`, which it does after both of
 *   its own transmitters exited.
 * - Give back: the transmitters stop first and must read quiet for a second,
 *   then a signed `give_back` tells the box to resume.
 * - Take-back: the box calls `POST /uplink/yield` ([YieldListener]) signed with
 *   the same key under the take-back's own MAC context; the phone stops, then
 *   answers. One during a take cancels it before the transmitters start
 *   opening, or is answered only once they opened and closed again
 *   ([TakeGate]): after an ok answer the phone never radiates for that take.
 * - Tie rule: while transmitting, a presence beacon from any other ground on
 *   this fleet's ground link stops the phone at once. The phone always yields.
 *
 * A take and a give-back run on the handover's own scope: a caller that goes
 * away (the screen closed) does not cut one short, and its outcome lands in
 * [state]. Every transition is logged under `AdosPhoneUplink` as
 * `uplink state <from> -> <to> reason=<…>`, every take-back call as
 * `uplink yield received from=<box> verdict=<…>`.
 */
@Singleton
class UplinkHandover internal constructor(
    private val radio: HandoverRadio,
    private val boxes: BoxDirectory,
    private val api: BoxUplinkApi,
    private val holderId: () -> String,
    private val holderName: () -> String,
    private val scope: CoroutineScope,
    private val clock: HandoverClock = HandoverClock.System,
    private val yieldPort: Int = YieldListener.PORT,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    @Inject constructor(
        session: PhoneRadioSession,
        store: InviteStore,
        vault: CredentialVault,
        groundId: PhoneGroundId,
        api: BoxUplinkApi,
    ) : this(
        radio = SessionHandoverRadio(session, appScope),
        boxes = InviteBoxAccess(store, vault),
        api = api,
        holderId = { groundId.value },
        holderName = ::phoneHolderName,
        scope = appScope,
    )

    private val _state = MutableStateFlow(HandoverState())
    val state: StateFlow<HandoverState> = _state.asStateFlow()

    private val ops = Mutex()
    private val guard = YieldGuard()
    private val otherGrounds = ConcurrentHashMap<String, Long>()
    private var listener: YieldListener? = null

    /** The take-back listener's bound port while it serves; null otherwise. */
    internal val yieldListenerPort: Int? get() = listener?.localPort
    private var takenFrom: String? = null
    private var takenAtMs = 0L

    /**
     * The box of the take in progress or held, with its handover key, from
     * the take until the handover ends: a take-back call is checked against
     * it without opening the vault.
     */
    @Volatile private var held: BoxAccess? = null

    /** The take in progress or last settled; read after [held] by a take-back call. */
    @Volatile private var gate: TakeGate? = null

    init {
        scope.launch { radio.groundBeacons.collect(::onBeacon) }
        // Level-triggered: after each check, a fresh subscription waits for the
        // radio's holding or the state to differ from what was checked. A
        // long-lived collector of `holding` misses transmitters that open and
        // drop before it runs (StateFlow conflates true -> false, and a take
        // ignores both while busy), leaving the state transmitting with the
        // radio silent.
        scope.launch {
            while (true) {
                val checked = ops.withLock { reconcileHolding() }
                combine(radio.holding, _state, ::Pair).first { it != checked }
            }
        }
    }

    /** Takes the uplink from the box; a refusal is the result's [HandoverState.note]. */
    suspend fun take(): HandoverState = scope.async { ops.withLock { takeLocked() } }.await()

    private suspend fun takeLocked(): HandoverState {
        val start = _state.value
        if (start.phase == HandoverPhase.Taking || start.phase == HandoverPhase.Transmitting) return start
        fun refuse(note: HandoverNote): HandoverState {
            log("uplink take refused reason=${note.english}")
            return HandoverState(start.phase, note = note).also { _state.value = it }
        }
        if (!radio.receiving.value) return refuse(HandoverNote.NoRadio)
        val box = boxes.load() ?: return refuse(HandoverNote.NoHandoverKey)
        val auth = box.auth ?: return refuse(HandoverNote.NoHandoverKey)
        val target = box.target ?: return refuse(HandoverNote.NoLanReach(box.name))
        val now = clock.elapsedMs()
        otherGrounds.entries.firstOrNull { it.key != box.deviceId && now - it.value < PEER_STALE_MS }
            ?.let { return refuse(HandoverNote.OtherGround(it.key)) }
        val yielder = listener ?: YieldListener(yieldPort, scope, ::onYield).also { listener = it }
        if (!yielder.start()) return refuse(HandoverNote.ListenerFailed(yieldPort))
        val take = TakeGate().also { gate = it }
        held = box
        move(HandoverState(HandoverPhase.Taking, busy = true), "take requested from ${box.deviceId}")
        var holdTried = false
        // The transmitters are known closed; what a waiting take-back is answered with.
        var quiet = true
        try {
            val request = SignedRequest.take(holderId(), holderName(), yielder.localPort ?: yieldPort, auth, clock.wallMs())
            val reply = api.post(target, request)
            val standby = reply is BoxReply.Ok && reply.state == STANDBY
            if (standby && !take.open()) {
                // The box's operator took it back before the phone started: nothing went out.
                return end(HandoverPhase.Yielded, HandoverNote.TakenBack(box.name))
            }
            if (standby) {
                holdTried = true
                quiet = false
                if (radio.hold()) {
                    take.close {
                        takenFrom = box.deviceId
                        takenAtMs = clock.elapsedMs()
                        move(HandoverState(HandoverPhase.Transmitting, HoldSource.Taken), "box ${box.deviceId} in standby")
                    } ?: return _state.value
                    // Taken back while the transmitters opened: they close before the call is answered.
                    quiet = withContext(NonCancellable) { radio.release() }
                    return end(HandoverPhase.Yielded, if (quiet) HandoverNote.TakenBack(box.name) else HandoverNote.TxNotQuiet(box.name))
                }
                quiet = true
                // The box stopped for nothing: hand it straight back, unless its operator already took it back.
                if (!take.takenBack()) api.post(target, SignedRequest.giveBack(holderId(), auth, clock.wallMs()))
                return end(HandoverPhase.ReceiveOnly, HandoverNote.TxDidNotOpen(box.name))
            }
            val note = when (reply) {
                is BoxReply.Refused -> HandoverNote.BoxRefused(reply.detail)
                is BoxReply.Ok -> HandoverNote.BoxRefused(
                    "${box.name} answered ${reply.state ?: "without a state"} rather than standby, so the phone did not transmit.",
                )
                BoxReply.Unreachable -> HandoverNote.NoLanReach(box.name)
            }
            return end(HandoverPhase.ReceiveOnly, note)
        } finally {
            if (_state.value.phase == HandoverPhase.Taking) {
                quiet = withContext(NonCancellable) { abandonTake(box.name, target, auth, holdTried, take) }
            }
            take.close()?.complete(quiet)
        }
    }

    /**
     * A take that ended without settling (it threw, or the handover's own
     * scope ended): the box may already sit in standby, so the phone makes
     * sure it is quiet and hands the uplink straight back, unless the box's
     * operator already took it back. True when the transmitters read quiet.
     */
    private suspend fun abandonTake(boxName: String, target: BoxTarget, auth: PhoneAuth, holdTried: Boolean, take: TakeGate): Boolean {
        var note: HandoverNote = HandoverNote.TxNotQuiet(boxName)
        try {
            if (holdTried && !radio.release()) return false
            note = HandoverNote.TxDidNotOpen(boxName)
            if (!take.takenBack()) api.post(target, SignedRequest.giveBack(holderId(), auth, clock.wallMs()))
            return true
        } finally {
            end(HandoverPhase.ReceiveOnly, note)
        }
    }

    /** Gives a taken uplink back: stop, confirm quiet for a second, then tell the box. */
    suspend fun giveBack(): HandoverState = scope.async { ops.withLock { giveBackLocked() } }.await()

    private suspend fun giveBackLocked(): HandoverState {
        val start = _state.value
        if (start.phase != HandoverPhase.Transmitting || start.source != HoldSource.Taken) return start
        _state.value = start.copy(busy = true)
        val box = held
        val name = box?.name ?: takenFrom.orEmpty()
        var quiet: Boolean? = null
        try {
            val stopped = radio.release()
            quiet = stopped
            if (!stopped) return end(HandoverPhase.Yielded, HandoverNote.TxNotQuiet(name))
            val auth = box?.auth
            val target = box?.target
            val reply = if (auth != null && target != null) {
                api.post(target, SignedRequest.giveBack(holderId(), auth, clock.wallMs()))
            } else {
                BoxReply.Unreachable
            }
            return end(
                HandoverPhase.Yielded,
                when (reply) {
                    is BoxReply.Ok -> HandoverNote.GaveBack(name)
                    is BoxReply.Refused -> HandoverNote.GiveBackUnconfirmed(name, reply.detail)
                    BoxReply.Unreachable -> HandoverNote.GiveBackUnconfirmed(name, null)
                },
            )
        } finally {
            val now = _state.value
            if (now.busy && now.phase == HandoverPhase.Transmitting) {
                // The give-back ended without settling: the phone stops for
                // certain and the box resumes on its own fallback.
                withContext(NonCancellable) {
                    var stopped = quiet ?: false
                    try {
                        if (quiet == null) stopped = radio.release()
                    } finally {
                        end(
                            HandoverPhase.Yielded,
                            if (stopped) HandoverNote.GiveBackUnconfirmed(name, null) else HandoverNote.TxNotQuiet(name),
                        )
                    }
                }
            }
        }
    }

    /** The box and its uplink as its LAN route reports it now; null with no ground-role invite. */
    suspend fun boxStatus(): Pair<BoxAccess, BoxStatus>? {
        val box = boxes.load() ?: return null
        val target = box.target ?: return box to BoxStatus.Unreachable
        return box to api.status(target)
    }

    private suspend fun onBeacon(beacon: GroundBeacon) {
        val now = clock.elapsedMs()
        if (beacon.deviceId != boxes.boxId()) otherGrounds[beacon.deviceId] = now
        val current = _state.value
        if (current.phase != HandoverPhase.Transmitting || current.busy || current.source == HoldSource.Bench) return
        // The box's last beacon before it stopped may still be in the receive path.
        if (current.source == HoldSource.Taken && beacon.deviceId == takenFrom && now - takenAtMs < TAKE_GRACE_MS) return
        stopAtOnce(HandoverNote.OtherGround(beacon.deviceId))
    }

    private suspend fun onYield(request: YieldRequest): YieldAnswer {
        val box = held
        val from = runCatching {
            ((Json.parseToJsonElement(request.body.decodeToString()) as JsonObject)["box_device_id"] as JsonPrimitive).content
        }.getOrNull()
        val key = box?.auth?.handoverKey
        val verdict = if (box != null && key != null) {
            guard.check(key, request.tsMs, request.body, request.macB64, box.deviceId, clock.wallMs())
        } else {
            null
        }
        log("uplink yield received from=${from ?: "unknown"} verdict=${verdict?.wire ?: "no_key"}")
        if (box == null || verdict != MacVerdict.Accepted) {
            val detail = if (verdict == MacVerdict.NotAYield) {
                "The call is not a take-back from the ground station this phone took the uplink from."
            } else {
                "The take-back call does not verify under this phone's handover key."
            }
            return YieldAnswer(401, YieldListener.error("E_UPLINK_UNAUTHORIZED", detail))
        }
        when (val during = gate?.takeBack() ?: TakeGate.TakeBack.Settled) {
            TakeGate.TakeBack.Cancelled -> return YieldAnswer(200, """{"ok":true,"state":"taking"}""")
            is TakeGate.TakeBack.Waiting -> return stoppedAnswer(during.quiet.await())
            TakeGate.TakeBack.Settled -> Unit
        }
        if (_state.value.phase != HandoverPhase.Transmitting) {
            return YieldAnswer(200, """{"ok":true,"state":"${_state.value.phase.wire}"}""")
        }
        return stoppedAnswer(stopAtOnce(HandoverNote.TakenBack(box.name)))
    }

    private fun stoppedAnswer(quiet: Boolean): YieldAnswer = if (quiet) {
        YieldAnswer(200, """{"ok":true,"state":"yielded"}""")
    } else {
        YieldAnswer(409, YieldListener.error("E_TX_NOT_QUIET", "The phone closed its transmitters but they did not read quiet within five seconds."))
    }

    /**
     * Closes the transmitters now, then moves to yielded with [note]; true
     * when they read quiet. Runs to the end even when its caller is cancelled.
     */
    private suspend fun stopAtOnce(note: HandoverNote): Boolean = withContext(NonCancellable) {
        _state.update { if (it.phase == HandoverPhase.Transmitting) it.copy(busy = true) else it }
        try {
            radio.release()
        } finally {
            ops.withLock {
                if (_state.value.phase == HandoverPhase.Transmitting) end(HandoverPhase.Yielded, note)
            }
        }
    }

    /**
     * Brings the state in line with the radio's holding now, between takes
     * and give-backs; the holding and state it checked.
     */
    private fun reconcileHolding(): Pair<Boolean, HandoverState> {
        val holding = radio.holding.value
        val current = _state.value
        if (current.busy) return holding to current
        when {
            holding && (current.phase == HandoverPhase.ReceiveOnly || current.phase == HandoverPhase.Yielded) -> {
                // A run that holds the uplink from its start: the fleet bound here, or the bench relay.
                val source = if (boxes.boxId() == null) HoldSource.OwnFleet else HoldSource.Bench
                move(HandoverState(HandoverPhase.Transmitting, source), if (source == HoldSource.Bench) "bench relay" else "own fleet")
            }
            !holding && current.phase == HandoverPhase.Transmitting -> {
                if (current.source == HoldSource.Bench) {
                    move(HandoverState(HandoverPhase.ReceiveOnly), "bench run ended")
                } else {
                    end(HandoverPhase.Yielded, HandoverNote.RadioStopped)
                }
            }
        }
        return holding to _state.value
    }

    /** A move that ends a take, give-back or yield: the listener closes with it. */
    private fun end(phase: HandoverPhase, note: HandoverNote): HandoverState {
        listener?.close()
        held = null
        takenFrom = null
        return move(HandoverState(phase, note = note), note.english)
    }

    private fun move(next: HandoverState, reason: String): HandoverState {
        val from = _state.value
        _state.value = next
        if (from.phase != next.phase || from.source != next.source) {
            log("uplink state ${from.phase.wire} -> ${next.phase.wire} reason=$reason")
        }
        return next
    }

    internal companion object {
        const val TAG = "AdosPhoneUplink"
        const val STANDBY = "standby"

        /** A holder silent this long is gone: the box's own fallback, and how long another ground's beacon blocks a take. */
        const val PEER_STALE_MS = 25_000L

        /** Beacons from the box itself are ignored this long after it confirmed standby. */
        const val TAKE_GRACE_MS = 2_000L

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
