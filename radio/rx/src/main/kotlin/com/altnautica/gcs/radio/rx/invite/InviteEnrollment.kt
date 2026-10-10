package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Progress of asking a ground station for the radio keys. */
sealed interface EnrollmentState {
    data object Requesting : EnrollmentState

    /** Waiting for the operator; [fingerprint] must match what the ground station shows. */
    data class AwaitingApproval(val fingerprint: String, val expiresAtMs: Long) : EnrollmentState
    data class Approved(val invite: StoredInvite) : EnrollmentState

    /**
     * The keys opened but the credential vault refused to store them. The ground
     * station hands a bundle out once, so [invite] is held here to [InviteEnrollment.save] again.
     */
    data class NotSaved(val invite: StoredInvite) : EnrollmentState
    data object Rejected : EnrollmentState
    data object Expired : EnrollmentState

    /** The node answered that it does not issue phone invites. */
    data object Unsupported : EnrollmentState
    data class Failed(val reason: InviteFailure) : EnrollmentState

    /** The approved bundle did not open or did not validate. */
    data class InvalidInvite(val detail: String) : EnrollmentState
}

/**
 * Requests the radio keys from one ground station: generate an X25519 key
 * pair, register the public half, poll until the operator decides, then open
 * and store the sealed bundle. The secret lives only for the duration of
 * one [run] and is wiped on every exit path.
 */
class InviteEnrollment @Inject constructor(
    private val api: PhoneInviteApi,
    private val cipher: InviteCipher,
    private val store: InviteStore,
) {
    /** [wallClock] gives epoch ms for the stored approval time. */
    internal var wallClock: () -> Long = System::currentTimeMillis

    fun run(ground: DeviceId, groundName: String?, label: String): Flow<EnrollmentState> = flow {
        emit(EnrollmentState.Requesting)
        val keys = cipher.generateKeyPair()
        try {
            val created = when (val r = api.create(ground, keys.public, label)) {
                is InviteCallResult.Ok -> r.value
                InviteCallResult.Unsupported -> {
                    emit(EnrollmentState.Unsupported)
                    return@flow
                }
                is InviteCallResult.Failed -> {
                    emit(EnrollmentState.Failed(r.reason))
                    return@flow
                }
            }
            if (!created.phoneFingerprint.equals(keys.fingerprint, ignoreCase = true)) {
                // Something between the phone and the ground station swapped the key.
                emit(EnrollmentState.InvalidInvite("the ground station registered a different phone key"))
                return@flow
            }
            emit(EnrollmentState.AwaitingApproval(keys.fingerprint, created.expiresAtMs))
            var networkMisses = 0
            while (true) {
                delay(POLL_MS)
                when (val r = api.poll(ground, created.inviteId)) {
                    is InviteCallResult.Ok -> when (val p = r.value) {
                        InvitePoll.Pending -> networkMisses = 0
                        InvitePoll.Rejected -> {
                            emit(EnrollmentState.Rejected)
                            return@flow
                        }
                        InvitePoll.Expired -> {
                            emit(EnrollmentState.Expired)
                            return@flow
                        }
                        is InvitePoll.Approved -> {
                            emit(open(keys, p.sealed, created.inviteId, ground, groundName))
                            return@flow
                        }
                    }
                    InviteCallResult.Unsupported -> {
                        emit(EnrollmentState.Expired)
                        return@flow
                    }
                    is InviteCallResult.Failed -> {
                        // Ride out a brief Wi-Fi drop; the request lives two minutes.
                        if (r.reason != InviteFailure.Network || ++networkMisses > MAX_NETWORK_MISSES) {
                            emit(EnrollmentState.Failed(r.reason))
                            return@flow
                        }
                    }
                }
            }
        } finally {
            keys.wipe()
        }
    }

    private suspend fun open(
        keys: PhoneKeyPair,
        sealed: SealedInvite,
        inviteId: String,
        ground: DeviceId,
        groundName: String?,
    ): EnrollmentState {
        val plain = cipher.open(keys.secret, sealed, InviteContext.of(inviteId))
            ?: return EnrollmentState.InvalidInvite("the sealed keys did not open with this phone's key")
        val bundle = try {
            InviteBundle.parse(plain)
        } catch (e: InvalidInviteException) {
            return EnrollmentState.InvalidInvite(e.message ?: "invalid invite")
        } finally {
            plain.fill(0)
        }
        val stored = StoredInvite(bundle, ground.value, groundName, keys.fingerprint, wallClock())
        return save(stored)
    }

    /** Stores an opened invite: [EnrollmentState.Approved], or [EnrollmentState.NotSaved] while the vault refuses writes. */
    suspend fun save(invite: StoredInvite): EnrollmentState = try {
        store.save(invite)
        EnrollmentState.Approved(invite)
    } catch (_: VaultUnreadableException) {
        EnrollmentState.NotSaved(invite)
    }

    companion object {
        const val POLL_MS = 1_500L
        private const val MAX_NETWORK_MISSES = 5
    }
}
