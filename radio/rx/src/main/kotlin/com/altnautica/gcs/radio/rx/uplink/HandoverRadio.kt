package com.altnautica.gcs.radio.rx.uplink

import android.os.Build
import android.os.SystemClock
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.session.GroundBeacon
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.session.PhoneRadioState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Base64

/** What the handover needs of the phone radio. */
interface HandoverRadio {
    /** The radio is up and receiving. */
    val receiving: StateFlow<Boolean>

    /** The phone's ground transmitters are open. */
    val holding: StateFlow<Boolean>

    /** Other grounds' presence beacons on this fleet's ground link. */
    val groundBeacons: Flow<GroundBeacon>

    /** Opens the ground transmitters; true once transmitting. */
    suspend fun hold(): Boolean

    /** Closes them at once, then true once the sent-frame count stayed flat for a second. */
    suspend fun release(): Boolean
}

/** [HandoverRadio] over the phone-radio session's hooks. */
class SessionHandoverRadio(private val session: PhoneRadioSession, scope: CoroutineScope) : HandoverRadio {
    override val receiving: StateFlow<Boolean> =
        session.state.map { it is PhoneRadioState.Receiving }.stateIn(scope, SharingStarted.Eagerly, false)
    override val holding: StateFlow<Boolean> =
        session.uplink.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)
    override val groundBeacons: Flow<GroundBeacon> get() = session.groundBeacons
    override suspend fun hold(): Boolean = session.holdUplink()
    override suspend fun release(): Boolean = session.releaseUplink()
}

/** The box the stored invite came from, and what the phone needs to take its uplink. */
class BoxAccess(
    val deviceId: String,
    val name: String,
    /** The box's uplink route on its LAN address; null when the invite carries no address. */
    val target: BoxTarget?,
    /** Null when the invite carries no handover key or the phone's invite key was not kept. */
    val auth: PhoneAuth?,
)

/** Where the box the phone may take the uplink from is found. */
interface BoxDirectory {
    /** The box id of the stored invite, read without touching the vault; null with no ground role. */
    fun boxId(): String?

    /** Null when no invite with a ground role is stored. */
    suspend fun load(): BoxAccess?
}

/** Reads the box's access from the stored invite and the paired node's key. */
class InviteBoxAccess(private val store: InviteStore, private val vault: CredentialVault) : BoxDirectory {

    override fun boxId(): String? = store.invite.value?.bundle?.ground?.boxDeviceId

    override suspend fun load(): BoxAccess? {
        val invite = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val ground = invite.bundle.ground ?: return null
        val key = runCatching { ground.uplinkKey }.getOrNull()?.takeIf { it.size == UplinkMac.KEY_BYTES }
        val pub = invite.phonePubB64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val apiKey = try {
            vault.node(DeviceId(invite.groundId))?.apiKey
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return BoxAccess(
            deviceId = ground.boxDeviceId,
            name = ground.boxName.ifBlank { invite.groundName ?: ground.boxDeviceId },
            target = ground.boxLanAddress?.let { BoxTarget.of(it, apiKey) },
            auth = if (key != null && pub != null) PhoneAuth(pub, key) else null,
        )
    }
}

/** Wall time for signatures, monotonic time for windows. */
interface HandoverClock {
    fun wallMs(): Long
    fun elapsedMs(): Long

    object System : HandoverClock {
        override fun wallMs(): Long = java.lang.System.currentTimeMillis()
        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    }
}

/** The name a box shows for this phone while it holds the uplink: maker and model, at most 64 characters. */
fun phoneHolderName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
    .filter { !it.isNullOrBlank() }
    .joinToString(" ")
    .ifBlank { "Android phone" }
    .take(64)
