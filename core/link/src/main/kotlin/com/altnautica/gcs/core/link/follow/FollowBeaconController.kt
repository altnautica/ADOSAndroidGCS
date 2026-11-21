package com.altnautica.gcs.core.link.follow

import com.altnautica.gcs.core.data.follow.FollowBeaconSettings
import com.altnautica.gcs.core.data.follow.FollowBeaconSettingsRepository
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.Reach
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Share my position for follow-me": streams the phone's position to the flown vehicle as the
 * follow beacon while the operator has it on, a vehicle is connected, follow-me is installed on
 * it, precise location is granted, and the app is in front or has been in the background for no
 * longer than the operator's grace period. Any of those ending stops the stream at once; turning
 * sharing on is always the operator's act, never this class's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowBeaconController(
    private val scope: CoroutineScope,
    private val phone: PhonePosition,
    private val settings: FollowBeaconSettingsRepository,
    services: StateFlow<VehicleServices?>,
    followAvailable: StateFlow<Boolean>,
) {
    private val permission = MutableStateFlow(phone.hasPermission())

    private val connected: StateFlow<VehicleServices?> = services.flatMapLatest { svc ->
        svc?.vehicle?.reach?.map { r -> svc.takeIf { r.connected() } } ?: flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The elapsed time the app went to the background; null while it is in front. */
    private val backgroundSince: StateFlow<Long?> = phone.foreground
        .map { front -> if (front) null else phone.nowMs() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** True while the toggle may be offered: a connected vehicle with follow-me. */
    val available: StateFlow<Boolean> = combine(connected, followAvailable) { svc, follow -> svc != null && follow }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _share = MutableStateFlow<BeaconShare>(BeaconShare.Off)

    /** What the beacon is doing, for the status chip. */
    val share: StateFlow<BeaconShare> = _share.asStateFlow()

    init {
        // Back in front: the permission may have changed in system settings meanwhile.
        scope.launch { phone.foreground.drop(1).filter { it }.collect { permission.value = phone.hasPermission() } }
        scope.launch {
            combine(settings.settings, connected, followAvailable, permission, backgroundSince) { s, svc, follow, perm, bg ->
                Triple(s, svc, ShareInputs(svc != null, follow, perm, bg))
            }.collectLatest { (s, svc, inputs) ->
                if (!s.sharePosition) {
                    _share.value = BeaconShare.Off
                    return@collectLatest
                }
                val block = FollowBeaconPolicy.block(inputs, phone.nowMs(), s.graceMs())
                if (block != null || svc == null) {
                    _share.value = BeaconShare.Blocked(block ?: ShareBlock.NoVehicle)
                    return@collectLatest
                }
                stream(svc, s, inputs)
                _share.value = BeaconShare.Blocked(ShareBlock.Background)
            }
        }
    }

    /** Turns sharing on or off; the choice persists. */
    fun setEnabled(on: Boolean) {
        if (on) permission.value = phone.hasPermission()
        scope.launch { settings.setSharePosition(on) }
    }

    /** After a permission request: re-reads the grant. */
    fun permissionChanged() {
        permission.value = phone.hasPermission()
    }

    /**
     * Streams until cancelled by a change of conditions, or until the background grace runs out
     * (then returns). A withdrawn permission ends the fixes; the permission flag then restarts the
     * decision, which blocks.
     */
    private suspend fun stream(svc: VehicleServices, s: FollowBeaconSettings, inputs: ShareInputs) {
        val fixes = MutableStateFlow<BeaconFix?>(null)
        svc.followBeacon.start(fixes)
        try {
            coroutineScope {
                val work = launch {
                    launch {
                        try {
                            phone.fixes().collect { fixes.value = it }
                        } catch (_: SecurityException) {
                            permission.value = false
                        }
                    }
                    while (true) {
                        _share.value = chip(svc.followBeacon.lastSent.value, fixes.value, phone.nowMs())
                        delay(CHIP_PERIOD_MS)
                    }
                }
                inputs.backgroundSinceMs?.let { since ->
                    delay((since + s.graceMs() - phone.nowMs()).coerceAtLeast(0))
                    work.cancel()
                }
            }
        } finally {
            svc.followBeacon.stop()
        }
    }

    private companion object {
        const val CHIP_PERIOD_MS = 500L

        fun FollowBeaconSettings.graceMs(): Long = backgroundGraceS * 1_000L

        fun Reach.connected(): Boolean = this == Reach.DirectFc || this == Reach.Lan || this is Reach.ViaGround

        fun chip(sent: BeaconFix?, newest: BeaconFix?, nowMs: Long): BeaconShare = when {
            sent != null && FollowBeaconPolicy.fresh(sent, nowMs) -> BeaconShare.Sharing(sent.horizAccM)
            newest != null && FollowBeaconPolicy.fresh(newest, nowMs) -> BeaconShare.Blocked(ShareBlock.NotSent)
            else -> BeaconShare.Blocked(ShareBlock.NoFix)
        }
    }
}

/** Builds the [FollowBeaconController] of one cockpit. */
class FollowBeaconControllerFactory @Inject constructor(
    private val phone: PhonePosition,
    private val settings: FollowBeaconSettingsRepository,
) {
    fun create(scope: CoroutineScope, services: StateFlow<VehicleServices?>, followAvailable: StateFlow<Boolean>): FollowBeaconController =
        FollowBeaconController(scope, phone, settings, services, followAvailable)
}
