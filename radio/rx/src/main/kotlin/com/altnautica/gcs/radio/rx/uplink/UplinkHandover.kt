package com.altnautica.gcs.radio.rx.uplink

import android.util.Log
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.session.GroundBeacon
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.tx.PhoneGroundId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *   the same key; the phone stops, then answers.
 * - Tie rule: while transmitting, a presence beacon from any other ground on
 *   this fleet's ground link stops the phone at once. The phone always yields.
 *
 * Every transition is logged under `AdosPhoneUplink` as
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
    private val guard = UplinkReplayGuard()
    private val otherGrounds = ConcurrentHashMap<String, Long>()
    private var listener: YieldListener? = null

    /** The take-back listener's bound port while it serves; null otherwise. */
    internal val yieldListenerPort: Int? get() = listener?.localPort
    private var takenFrom: String? = null
    private var takenAtMs = 0L

    /** A take-back arrived while the take was still in flight. */
    @Volatile private var yieldDuringTake = false

    init {
        scope.launch { radio.groundBeacons.collect(::onBeacon) }
        scope.launch { radio.holding.collect(::onHolding) }
    }

    /** Takes the uplink from the box; a refusal is the result's [HandoverState.note]. */
    suspend fun take(): HandoverState = ops.withLock {
        val start = _state.value
        if (start.phase == HandoverPhase.Taking || start.phase == HandoverPhase.Transmitting) return@withLock start
        fun refuse(note: HandoverNote): HandoverState {
            log("uplink take refused reason=${note.english}")
            return HandoverState(start.phase, note = note).also { _state.value = it }
        }
        if (!radio.receiving.value) return@withLock refuse(HandoverNote.NoRadio)
        val box = boxes.load() ?: return@withLock refuse(HandoverNote.NoHandoverKey)
        val auth = box.auth ?: return@withLock refuse(HandoverNote.NoHandoverKey)
        val target = box.target ?: return@withLock refuse(HandoverNote.NoLanReach(box.name))
        val now = clock.elapsedMs()
        otherGrounds.entries.firstOrNull { it.key != box.deviceId && now - it.value < PEER_STALE_MS }
            ?.let { return@withLock refuse(HandoverNote.OtherGround(it.key)) }
        val yielder = listener ?: YieldListener(yieldPort, scope, ::onYield).also { listener = it }
        if (!yielder.start()) return@withLock refuse(HandoverNote.ListenerFailed(yieldPort))
        yieldDuringTake = false
        move(HandoverState(HandoverPhase.Taking, busy = true), "take requested from ${box.deviceId}")
        val request = SignedRequest.take(holderId(), holderName(), yielder.localPort ?: yieldPort, auth, clock.wallMs())
        val reply = api.post(target, request)
        val standby = reply is BoxReply.Ok && reply.state == STANDBY
        if (standby && yieldDuringTake) {
            // The box's operator took it back before the phone started: nothing went out.
            return@withLock end(HandoverPhase.Yielded, HandoverNote.TakenBack(box.name))
        }
        if (standby && radio.hold()) {
            takenFrom = box.deviceId
            takenAtMs = clock.elapsedMs()
            return@withLock move(HandoverState(HandoverPhase.Transmitting, HoldSource.Taken), "box ${box.deviceId} in standby")
        }
        if (standby) {
            // The box stopped for nothing: hand it straight back.
            api.post(target, SignedRequest.giveBack(holderId(), auth, clock.wallMs()))
            return@withLock end(HandoverPhase.ReceiveOnly, HandoverNote.TxDidNotOpen(box.name))
        }
        val note = when (reply) {
            is BoxReply.Refused -> HandoverNote.BoxRefused(reply.detail)
            is BoxReply.Ok -> HandoverNote.BoxRefused(
                "${box.name} answered ${reply.state ?: "without a state"} rather than standby, so the phone did not transmit.",
            )
            BoxReply.Unreachable -> HandoverNote.NoLanReach(box.name)
        }
        end(HandoverPhase.ReceiveOnly, note)
    }

    /** Gives a taken uplink back: stop, confirm quiet for a second, then tell the box. */
    suspend fun giveBack(): HandoverState = ops.withLock {
        val start = _state.value
        if (start.phase != HandoverPhase.Transmitting || start.source != HoldSource.Taken) return@withLock start
        _state.value = start.copy(busy = true)
        val box = boxes.load()
        val name = box?.name ?: takenFrom.orEmpty()
        if (!radio.release()) return@withLock end(HandoverPhase.Yielded, HandoverNote.TxNotQuiet(name))
        val auth = box?.auth
        val target = box?.target
        val reply = if (auth != null && target != null) {
            api.post(target, SignedRequest.giveBack(holderId(), auth, clock.wallMs()))
        } else {
            BoxReply.Unreachable
        }
        end(
            HandoverPhase.Yielded,
            when (reply) {
                is BoxReply.Ok -> HandoverNote.GaveBack(name)
                is BoxReply.Refused -> HandoverNote.GiveBackUnconfirmed(name, reply.detail)
                BoxReply.Unreachable -> HandoverNote.GiveBackUnconfirmed(name, null)
            },
        )
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
        val box = boxes.load()
        val from = runCatching {
            ((Json.parseToJsonElement(request.body.decodeToString()) as JsonObject)["box_device_id"] as JsonPrimitive).content
        }.getOrNull()
        val verdict = box?.auth?.let { guard.check(it.handoverKey, request.tsMs, request.body, request.macB64, clock.wallMs()) }
        log("uplink yield received from=${from ?: "unknown"} verdict=${verdict?.wire ?: "no_key"}")
        if (verdict != MacVerdict.Accepted) {
            return YieldAnswer(401, YieldListener.error("E_UPLINK_UNAUTHORIZED", "The take-back call does not verify under this phone's handover key."))
        }
        when (_state.value.phase) {
            HandoverPhase.Taking -> {
                yieldDuringTake = true
                return YieldAnswer(200, """{"ok":true,"state":"taking"}""")
            }
            HandoverPhase.Transmitting -> Unit
            else -> return YieldAnswer(200, """{"ok":true,"state":"${_state.value.phase.wire}"}""")
        }
        val quiet = stopAtOnce(HandoverNote.TakenBack(box?.name ?: from.orEmpty()))
        return if (quiet) {
            YieldAnswer(200, """{"ok":true,"state":"yielded"}""")
        } else {
            YieldAnswer(409, YieldListener.error("E_TX_NOT_QUIET", "The phone closed its transmitters but they did not read quiet within five seconds."))
        }
    }

    /** Closes the transmitters now, then moves to yielded with [note]; true when they read quiet. */
    private suspend fun stopAtOnce(note: HandoverNote): Boolean {
        _state.value = _state.value.copy(busy = true)
        val quiet = radio.release()
        ops.withLock {
            if (_state.value.phase == HandoverPhase.Transmitting) end(HandoverPhase.Yielded, note)
        }
        return quiet
    }

    private fun onHolding(holding: Boolean) {
        val current = _state.value
        if (current.busy) return
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
    }

    /** A move that ends a take, give-back or yield: the listener closes with it. */
    private fun end(phase: HandoverPhase, note: HandoverNote): HandoverState {
        listener?.close()
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
