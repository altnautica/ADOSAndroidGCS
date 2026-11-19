package com.altnautica.gcs.radio.rx.bind

import android.content.Context
import android.util.Log
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.link.fleet.FleetGroundStore
import com.altnautica.gcs.radio.rx.AlternateRadio
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.usb.UsbDongleMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** Where the phone's over-the-air bind stands. */
sealed interface BindState {
    data object Idle : BindState
    data class Running(val stage: BindStage, val peerHeard: Boolean = false) : BindState
    data class Paired(val droneVersion: String, val keyFingerprint: String) : BindState
    data class Failed(val reason: String) : BindState
}

/**
 * Runs "Bind a drone": stops the phone-radio session, binds on the bind
 * channel with the fleet's keys (fresh ones for a new fleet), stores them
 * once the drone is proven to have taken them, and starts the session again
 * as the fleet's ground.
 */
@Singleton
class BindController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: PhoneRadioSession,
    private val fleet: FleetGroundStore,
    private val invites: InviteStore,
    private val dongles: UsbDongleMonitor,
    private val alternate: Optional<AlternateRadio>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindThread = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-bind") }.asCoroutineDispatcher()
    private var job: Job? = null

    private val _state = MutableStateFlow<BindState>(BindState.Idle)
    val state: StateFlow<BindState> = _state.asStateFlow()

    /** Starts a bind; false while one runs. */
    @Synchronized
    fun start(): Boolean {
        if (job?.isActive == true) return false
        _state.value = BindState.Running(BindStage.PreparingKeys)
        job = scope.launch {
            val result = bind()
            Log.i(TAG, "bind result: $result")
            _state.value = result
        }
        return true
    }

    /** Cancels a running bind; the session starts again. */
    @Synchronized
    fun cancel() {
        job?.cancel()
    }

    private suspend fun bind(): BindState {
        val keys = try {
            if (invites.load() != null) {
                return BindState.Failed("This phone receives a box's fleet; forget its radio keys before binding a drone")
            }
            fleet.reusableKeys() ?: BindKeys.generate()
        } catch (_: VaultUnreadableException) {
            return BindState.Failed("The phone's keys could not be read just now; try again")
        } catch (e: IllegalStateException) {
            return BindState.Failed("New radio keys could not be made: ${e.message}")
        }
        val bindKey = context.assets.open(BindProfile.BIND_KEY_ASSET).use { it.readBytes() }
        session.stop()
        var radio: BindRadio? = null
        return try {
            _state.value = BindState.Running(BindStage.OpeningRadio)
            radio = BindRadio.open(context, dongles, alternate.orElse(null))
            val client = BindClient(bindKey, keys.droneKey, bindThread) { stage, heard ->
                Log.i(TAG, "bind stage $stage, drone heard $heard")
                _state.value = BindState.Running(stage, heard)
            }
            when (val outcome = client.run(radio)) {
                is BindOutcome.Failed -> BindState.Failed(outcome.reason)
                is BindOutcome.Paired -> {
                    _state.value = BindState.Running(BindStage.Storing, peerHeard = true)
                    fleet.adopt(keys)
                    BindState.Paired(outcome.droneVersion, keys.fingerprint)
                }
            }
        } catch (e: BindFailure) {
            BindState.Failed(e.message ?: "The bind did not run")
        } catch (e: VaultUnreadableException) {
            BindState.Failed("The drone took the key but the phone could not store it; bind again")
        } catch (e: CancellationException) {
            _state.value = BindState.Failed("Bind cancelled")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bind failed", e)
            BindState.Failed("The bind stopped: ${e.message}")
        } finally {
            withContext(NonCancellable) {
                radio?.close()
                session.start()
            }
        }
    }

    private companion object {
        const val TAG = "AdosPhoneBind"
    }
}
