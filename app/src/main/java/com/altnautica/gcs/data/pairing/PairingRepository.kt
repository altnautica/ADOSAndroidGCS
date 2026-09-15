package com.altnautica.gcs.data.pairing

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The LAN pairing handshake against a single agent, and the credential it
 * yields.
 *
 * This is the same contract the web GCS's Add-a-Node flow uses: probe
 * `GET /api/pairing/info` for the node's identity and whether it is already
 * claimed, then `POST /api/pairing/claim` to claim an unclaimed one and keep
 * the returned key. From then on every request carries the key in `X-ADOS-Key`
 * (see [AgentAuthInterceptor]) and the two ground-control clients can be used
 * against the same node.
 *
 * Pairing is local-first by construction: the probe and the claim are on the
 * agent's public route set, so being on the node's LAN is the gate. Nothing
 * here involves a cloud relay.
 */
@Singleton
class PairingRepository @Inject constructor(
    private val api: PairingApi,
    private val credentials: AgentCredentialStore,
) {

    companion object {
        private const val TAG = "PairingRepository"
    }

    /** The last probe result, or null before the first successful probe. */
    private val _info = MutableStateFlow<PairingInfo?>(null)
    val info: StateFlow<PairingInfo?> = _info.asStateFlow()

    /** True once the probe has reached the agent at least once. */
    private val _reachable = MutableStateFlow(false)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    /** The stored key, observable so the UI reflects a claim immediately. */
    val apiKey: Flow<String?> = credentials.apiKey
    val pairedDeviceId: Flow<String?> = credentials.pairedDeviceId
    val pairedDeviceName: Flow<String?> = credentials.pairedDeviceName
    val pairedMdnsHost: Flow<String?> = credentials.pairedMdnsHost

    /** Probe the configured agent for its identity and pairing posture. */
    suspend fun refresh(): Result<PairingInfo> = try {
        val fresh = api.getInfo()
        _info.value = fresh
        _reachable.value = true
        Result.success(fresh)
    } catch (e: Exception) {
        Log.w(TAG, "pairing probe failed: ${e.message}")
        _reachable.value = false
        Result.failure(e)
    }

    /**
     * Claim the agent for this install and persist the returned key.
     *
     * A 409 means the node is already claimed — by Mission Control, or by
     * another handset. That is not an error to retry: the operator has to
     * unpair the node (from whichever client holds it) before this one can
     * claim it, so it is surfaced as [AlreadyPairedError].
     */
    suspend fun claim(): Result<ClaimResponse> = try {
        val response = api.claim(ClaimRequest(userId = credentials.operatorId()))
        val body = response.body()
        when {
            response.isSuccessful && body != null && body.apiKey.isNotBlank() -> {
                credentials.store(body)
                // Reflect the new posture without waiting for the next poll.
                refresh()
                Result.success(body)
            }
            response.code() == 409 -> Result.failure(
                AlreadyPairedError("this node is already paired; unpair it first"),
            )
            else -> Result.failure(
                IllegalStateException("pairing claim failed: HTTP ${response.code()}"),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "pairing claim failed: ${e.message}")
        Result.failure(e)
    }

    /**
     * Release this node's pairing, clearing the stored key on success.
     *
     * Needs the current key (the route is behind the agent's key gate), so it
     * only works while this install is the holder. The local key is dropped
     * only after the agent confirms, so a failed call does not leave the app
     * unable to talk to a node that is still paired to it.
     */
    suspend fun unpair(): Result<Unit> = try {
        val response = api.unpair()
        when {
            response.isSuccessful -> {
                credentials.clear()
                refresh()
                Result.success(Unit)
            }
            response.code() == 401 -> Result.failure(
                NotPairedError("this device does not hold the node's pairing key"),
            )
            else -> Result.failure(
                IllegalStateException("unpair failed: HTTP ${response.code()}"),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "unpair failed: ${e.message}")
        Result.failure(e)
    }

    /**
     * Drop the locally stored key without touching the node.
     *
     * The escape hatch for the case the node was unpaired elsewhere: the stored
     * key is then dead, `unpair` would 401 forever, and without this the app
     * would keep presenting a credential the node has forgotten.
     */
    suspend fun forgetLocalKey() {
        credentials.clear()
    }
}
