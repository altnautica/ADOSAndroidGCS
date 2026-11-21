package com.altnautica.gcs.core.link.follow

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
 * follow beacon while the operator has it on for that vehicle, the vehicle is connected over the
 * agent (LAN or through its ground station; never a flight controller on the phone, which has no
 * router to take it), follow-me is installed on it, precise location is granted, and the app is in
 * front or has been in the background for no longer than the operator's grace period. Any of those
 * ending stops the stream at once; turning sharing on is always the operator's act for one vehicle.
 *
 * "Sharing" is shown only while the vehicle's router reports its published count rising; a fix
 * that leaves the phone but is dropped on the vehicle reads [ShareBlock.NotConfirmed].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowBeaconController(
    private val scope: CoroutineScope,
    private val phone: PhonePosition,
    private val settings: FollowBeaconSettingsRepository,
    private val router: RouterBeaconStatus,
    services: StateFlow<VehicleServices?>,
    followAvailable: StateFlow<Boolean>,
) {
    private val permission = MutableStateFlow(phone.hasPermission())

    private val connected: StateFlow<VehicleServices?> = services.flatMapLatest { svc ->
        svc?.vehicle?.reach?.map { r -> svc.takeIf { r.agentReach() } } ?: flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val consent = connected.flatMapLatest { svc ->
        svc?.let { settings.sharePosition(it.vehicle.id.value) } ?: flowOf(false)
    }

    /** The elapsed time the app went to the background; null while it is in front. */
    private val backgroundSince: StateFlow<Long?> = phone.foreground
        .map { front -> if (front) null else phone.nowMs() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** True while the toggle may be offered: a vehicle connected over its agent, with follow-me. */
    val available: StateFlow<Boolean> = combine(connected, followAvailable) { svc, follow -> svc != null && follow }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _share = MutableStateFlow<BeaconShare>(BeaconShare.Off)

    /** What the beacon is doing, for the status chip. */
    val share: StateFlow<BeaconShare> = _share.asStateFlow()

    private data class Decision(val on: Boolean, val graceMs: Long, val svc: VehicleServices?, val inputs: ShareInputs)

    init {
        // Back in front: the permission may have changed in system settings meanwhile.
        scope.launch { phone.foreground.drop(1).filter { it }.collect { permission.value = phone.hasPermission() } }
        scope.launch {
            val choice = combine(consent, settings.backgroundGraceS) { on, graceS -> on to graceS * 1_000L }
            combine(choice, connected, followAvailable, permission, backgroundSince) { (on, graceMs), svc, follow, perm, bg ->
                Decision(on, graceMs, svc, ShareInputs(svc != null, follow, perm, bg))
            }.collectLatest { d ->
                if (!d.on) {
                    _share.value = BeaconShare.Off
                    return@collectLatest
                }
                val block = FollowBeaconPolicy.block(d.inputs, phone.nowMs(), d.graceMs)
                if (block != null || d.svc == null) {
                    _share.value = BeaconShare.Blocked(block ?: ShareBlock.NoVehicle)
                    return@collectLatest
                }
                stream(d.svc, d.graceMs, d.inputs)
                _share.value = BeaconShare.Blocked(ShareBlock.Background)
            }
        }
    }

    /** Turns sharing on or off for the connected vehicle; the choice persists for that vehicle only. */
    fun setEnabled(on: Boolean) {
        val vehicle = connected.value?.vehicle?.id ?: return
        if (on) permission.value = phone.hasPermission()
        scope.launch { settings.setSharePosition(vehicle.value, on) }
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
    private suspend fun stream(svc: VehicleServices, graceMs: Long, inputs: ShareInputs) {
        val fixes = MutableStateFlow<BeaconFix?>(null)
        val confirmedAt = MutableStateFlow<Long?>(null)
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
                    launch { confirm(svc, confirmedAt) }
                    while (true) {
                        _share.value = chip(svc.followBeacon.lastSent.value, fixes.value, confirmedAt.value, phone.nowMs())
                        delay(CHIP_PERIOD_MS)
                    }
                }
                inputs.backgroundSinceMs?.let { since ->
                    delay((since + graceMs - phone.nowMs()).coerceAtLeast(0))
                    work.cancel()
                }
            }
        } finally {
            svc.followBeacon.stop()
        }
    }

    /** Polls the router's published count; stamps [confirmedAt] each time it rises. */
    private suspend fun confirm(svc: VehicleServices, confirmedAt: MutableStateFlow<Long?>) {
        var last: Long? = null
        while (true) {
            val count = router.published(svc.vehicle.id, svc.vehicle.reach.value)
            if (count != null && last != null && count > last) confirmedAt.value = phone.nowMs()
            if (count != null) last = count
            delay(CONFIRM_PERIOD_MS)
        }
    }

    private companion object {
        const val CHIP_PERIOD_MS = 500L
        const val CONFIRM_PERIOD_MS = 1_000L

        /** A rise older than this no longer confirms sharing. */
        const val CONFIRM_FRESH_MS = 3_000L

        fun Reach.agentReach(): Boolean = this == Reach.Lan || this is Reach.ViaGround

        fun chip(sent: BeaconFix?, newest: BeaconFix?, confirmedAtMs: Long?, nowMs: Long): BeaconShare = when {
            sent != null && FollowBeaconPolicy.fresh(sent, nowMs) ->
                if (confirmedAtMs != null && nowMs - confirmedAtMs <= CONFIRM_FRESH_MS) {
                    BeaconShare.Sharing(sent.horizAccM)
                } else {
                    BeaconShare.Blocked(ShareBlock.NotConfirmed)
                }
            newest != null && FollowBeaconPolicy.fresh(newest, nowMs) -> BeaconShare.Blocked(ShareBlock.NotSent)
            else -> BeaconShare.Blocked(ShareBlock.NoFix)
        }
    }
}

/** Builds the [FollowBeaconController] of one cockpit. */
class FollowBeaconControllerFactory @Inject constructor(
    private val phone: PhonePosition,
    private val settings: FollowBeaconSettingsRepository,
    private val router: RouterBeaconStatus,
) {
    fun create(scope: CoroutineScope, services: StateFlow<VehicleServices?>, followAvailable: StateFlow<Boolean>): FollowBeaconController =
        FollowBeaconController(scope, phone, settings, router, services, followAvailable)
}
