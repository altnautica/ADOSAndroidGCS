package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.agent.pilot.PilotFrame
import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.hud.pilotFresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/** Where the stick streamer sends its frames: the pilot channel of the flown vehicle. */
internal interface StickSink {
    /** Starts a new stick session (a pad connected or the cockpit started). */
    fun beginSession()

    fun input(frame: PilotInput)

    /** Ends the stick session; the pilot brakes to a hold. */
    fun release()
}

/**
 * The pilot channel of one vehicle over `/api/v1/pilot/stream`: reconnects with
 * backoff, numbers stick sessions and frames, sends a 1 Hz heartbeat while no stick
 * input streams, and publishes the pilot state and action results it receives.
 * [connected] is true while server frames arrive: a gap of the spec's `stale_after_ms`
 * ([PilotState.STALE_MS]) or more drops the link. Frames are parsed in [parseContext] (off
 * the main thread in the app) and applied in the collector's, in arrival order.
 *
 * Every frame carries this app instance's [sid]. With no RC transmitter declared
 * (`rc_receiver: absent`) the heartbeat comes from [mainTicker] instead, and only while
 * [controlReachable]: a hung main thread, or an operator who can no longer reach Pause,
 * Land or Return, goes silent and the pilot's link-loss action fires. [leave] says `bye`
 * before a deliberate close.
 */
internal class PilotChannel(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val parseContext: CoroutineContext = EmptyCoroutineContext,
    private val controlReachable: () -> Boolean = { true },
    private val mainTicker: Ticker = MainLooperTicker(TICK_MS),
    private val sid: String = PilotWire.PROCESS_SID,
    private val open: (outgoing: Flow<String>) -> Flow<String>,
) : PilotLink, StickSink {
    override val src: String = PilotWire.SRC_ANDROID

    private val bye = PilotWire.bye(src, sid)

    /** Completes once the socket took the pending `bye`. */
    @Volatile private var byeSent: CompletableDeferred<Unit>? = null

    /** True while [mainTicker] runs: the pilot declares no RC. */
    @Volatile private var ticking = false

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _state = MutableStateFlow<PilotState?>(null)
    override val state: StateFlow<PilotState?> = _state.asStateFlow()

    private val _results = MutableSharedFlow<PilotFrame.ActionResult>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The pilot's answer to each action this surface sent. */
    val results: SharedFlow<PilotFrame.ActionResult> = _results.asSharedFlow()

    private val outgoing = Channel<String>(OUTGOING_CAPACITY, BufferOverflow.DROP_OLDEST)

    @Volatile private var session: Long = Random.nextLong(1, MAX_U32)
    @Volatile private var seq: Long = 0
    @Volatile private var lastInputMs: Long? = null
    @Volatile private var lastFrameMs: Long = 0
    @Volatile private var lastStateMs: Long? = null

    override val stateFresh: Boolean
        get() {
            val at = lastStateMs ?: return false
            return _connected.value && pilotFresh(at, clock.nowMs())
        }
    @Volatile private var lastHbMs: Long? = null
    private var actionId = 0L
    private var job: Job? = null
    private var connection: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { connectLoop() }
            while (isActive) {
                watchdog()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        mainTicker.stop()
        ticking = false
        job?.cancel()
        job = null
        connection = null
        _connected.value = false
    }

    /**
     * A deliberate close: says `bye` while the link is up, waits until the socket took it (at
     * most [BYE_FLUSH_MS]), then stops. The pilot decides whether the leave ends this
     * instance's link-loss watch.
     */
    suspend fun leave() {
        if (job == null) return
        if (_connected.value) {
            val sent = CompletableDeferred<Unit>().also { byeSent = it }
            if (outgoing.trySend(bye).isSuccess) withTimeoutOrNull(BYE_FLUSH_MS) { sent.await() }
        }
        stop()
    }

    /** The outgoing frames as the socket takes them; a `bye` counts as sent once taken. */
    private fun sentFrames(): Flow<String> = flow {
        outgoing.receiveAsFlow().collect { text ->
            emit(text)
            if (text === bye) byeSent?.complete(Unit)
        }
    }

    private suspend fun connectLoop() = coroutineScope {
        var backoff = RETRY_MS
        while (isActive) {
            // Frames queued for a dead socket are not replayed on the next one.
            while (outgoing.tryReceive().isSuccess) Unit
            val c = launch {
                try {
                    open(sentFrames())
                        .map { text -> clock.nowMs().let { now -> now to PilotWire.parseFrame(text, now) } }
                        .flowOn(parseContext)
                        .collect { (now, frame) ->
                            if (frame != null && onFrame(frame, now)) backoff = RETRY_MS
                        }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // Refused, unreachable or dropped: retried below.
                }
            }
            connection = c
            c.join()
            _connected.value = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    /** Applies one server frame; true when it was a pilot channel frame. */
    internal fun onText(text: String): Boolean {
        val now = clock.nowMs()
        return onFrame(PilotWire.parseFrame(text, now) ?: return false, now)
    }

    /** Applies one parsed server frame received at [now]; always true. */
    private fun onFrame(frame: PilotFrame, now: Long): Boolean {
        lastFrameMs = now
        _connected.value = true
        when (frame) {
            is PilotFrame.State -> {
                lastStateMs = now
                _state.value = frame.state
                // The main-looper tick runs only with no RC declared, so the looper idles otherwise.
                if (frame.state.noRc != ticking) {
                    ticking = frame.state.noRc
                    if (ticking) mainTicker.start(::mainTick) else mainTicker.stop()
                }
            }
            is PilotFrame.ActionResult -> _results.tryEmit(frame)
            is PilotFrame.Keepalive -> Unit
        }
        return true
    }

    /** Drops a silent link and, with an RC transmitter, keeps the link-loss timer alive while no input streams. */
    internal fun watchdog() {
        if (!_connected.value) return
        val now = clock.nowMs()
        if (now - lastFrameMs >= PilotState.STALE_MS) {
            _connected.value = false
            connection?.cancel()
            return
        }
        if (_state.value?.noRc != true) heartbeat(now)
    }

    /** The main-looper tick: with no RC, the heartbeat goes out only while a stop control is reachable. */
    internal fun mainTick() {
        if (_state.value?.noRc == true && controlReachable()) heartbeat(clock.nowMs())
    }

    private fun heartbeat(now: Long) {
        if (!_connected.value) return
        val last = lastInputMs
        val hb = lastHbMs
        if ((last == null || now - last >= HB_MS) && (hb == null || now - hb >= HB_MS)) {
            if (outgoing.trySend(PilotWire.hb(src, sid)).isSuccess) lastHbMs = now
        }
    }

    override fun beginSession() {
        session = if (session >= MAX_U32 - 1) 1 else session + 1
        seq = 0
    }

    override fun input(frame: PilotInput) {
        if (!_connected.value) return
        val now = clock.nowMs()
        seq = (seq + 1) and MAX_U32
        if (outgoing.trySend(PilotWire.input(src, sid, session, seq, now, frame)).isSuccess) lastInputMs = now
    }

    override fun release() {
        lastInputMs = null
        if (_connected.value) outgoing.trySend(PilotWire.release(src, sid, session))
    }

    override suspend fun action(action: PilotAction): PilotActionResult {
        if (!_connected.value) return PilotActionResult.Unavailable
        val id = synchronized(this) { (++actionId).toString() }
        return coroutineScope {
            // Listen before sending: the results flow keeps no replay.
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(ACTION_ANSWER_MS) { _results.first { it.id == id } }
            }
            if (!outgoing.trySend(PilotWire.action(action, src, sid, id)).isSuccess) {
                answer.cancel()
                return@coroutineScope PilotActionResult.Unavailable
            }
            val r = answer.await() ?: return@coroutineScope PilotActionResult.NoAnswer
            if (r.ok) PilotActionResult.Accepted else PilotActionResult.Refused(r.err)
        }
    }

    override fun takeSession(): Boolean {
        if (!_connected.value) return false
        return outgoing.trySend(PilotWire.sessionTake(src, sid, session)).isSuccess
    }

    companion object {
        const val TICK_MS = 250L
        const val HB_MS = 1_000L

        /** How long an action waits for the pilot's `action_result`. */
        const val ACTION_ANSWER_MS = 3_000L

        /** How long a deliberate close waits for the socket to take its `bye`. */
        const val BYE_FLUSH_MS = 500L
        const val RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 10_000L
        private const val OUTGOING_CAPACITY = 64
        private const val MAX_U32 = 0xFFFF_FFFFL
    }
}
