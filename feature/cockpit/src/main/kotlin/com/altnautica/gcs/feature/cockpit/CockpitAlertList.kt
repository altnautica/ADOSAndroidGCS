package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.model.ParamDocBook
import com.altnautica.gcs.core.model.ParamDocs
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.hud.AlertGroups
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.feature.cockpit.alerts.AlertSnoozeStore
import com.altnautica.gcs.feature.cockpit.alerts.ApplyProfileFix
import com.altnautica.gcs.feature.cockpit.chrome.StatusItem
import com.altnautica.gcs.feature.cockpit.chrome.StatusListUi
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The flown FC's parameter documentation (titles, units, value labels) for the alert rows; null while unknown or not bundled. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun paramDocBook(scope: CoroutineScope, telemetry: StateFlow<VehicleTelemetry?>, docs: ParamDocs): StateFlow<ParamDocBook?> = telemetry
    .flatMapLatest { it?.heartbeat ?: flowOf(null) }
    .map { hb -> hb?.let { it.autopilot to it.vehicleType } }
    .distinctUntilChanged()
    .mapLatest { k -> k?.let { docs.book(it.first, it.second) } }
    .stateIn(scope, SharingStarted.Eagerly, null)

/**
 * The status pill's alert list for [CockpitViewModel]: snoozed rows, the pill's count, the
 * list's sections, the blocking-only sheet and an alert row's inline Apply recommended values.
 */
internal class CockpitAlertList(
    private val scope: CoroutineScope,
    work: CoroutineContext,
    private val spec: CockpitSpecRepository,
    statusItems: StateFlow<List<StatusItem>>,
    waiting: StateFlow<Boolean>,
    private val snoozeStore: AlertSnoozeStore,
    private val overlays: CockpitOverlays,
    private val events: MutableSharedFlow<CockpitEvent>,
    abortScope: CoroutineScope,
    backend: suspend () -> SetupBackend?,
) {
    private val alertGroups by lazy { AlertGroups(spec.alertGroups) }

    /** The alert keys hidden on this device (`alert_snooze`). */
    val snoozed: StateFlow<Set<String>> = snoozeStore.snoozed.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The items the pill counts: snoozed warnings drop out, snoozed criticals stay. */
    val pillItems: StateFlow<List<StatusItem>> = combine(statusItems, snoozed, StatusModel::unsnoozed)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The alert list's sections (`alert-groups.json`). */
    val list: StateFlow<StatusListUi> = combine(statusItems, snoozed) { items, snoozed ->
        StatusModel.list(items, alertGroups, snoozed)
    }.stateIn(scope, SharingStarted.WhileSubscribed(), StatusListUi.Empty)

    /** "Apply recommended values" inline in an alert row, on the flown vehicle's setup engine. */
    val applyProfile = ApplyProfileFix(scope, spec.setupStrings, abortScope, backend)

    /** The status pill's alert list; Back closes it like a sheet. */
    val open: StateFlow<Boolean> = overlays.statusList.asStateFlow()

    /** True while the list shows only what blocks take-off (opened from the flight-state chip). */
    val blockingOnly = MutableStateFlow(false)

    init {
        // A snoozed key whose alert has cleared is dropped, so the alert shows again when it comes
        // back; nothing is pruned before the first pilot frame or heartbeat lists the live alerts.
        scope.launch(work) {
            combine(statusItems, waiting) { items, w -> if (w) null else items.map { it.snoozeKey } }
                .collect { keys -> if (keys != null) snoozeStore.prune(keys) }
        }
        // The review holds the profile step open on the vehicle: the list closing, or the row's
        // alert clearing, ends it.
        scope.launch {
            overlays.statusList.collect { open -> if (!open) applyProfile.leave() }
        }
        scope.launch {
            combine(statusItems, applyProfile.state) { items, s -> s.owner != null && items.none { it.snoozeKey == s.owner } }
                .collect { gone -> if (gone) applyProfile.leave() }
        }
    }

    /** Open or close the full alert list. */
    fun setOpen(open: Boolean) {
        blockingOnly.value = false
        overlays.statusList.value = open
    }

    /** The flight-state chip's sheet: the blocking rows first, with a link to every warning. */
    fun openBlocking() {
        overlays.statusList.value = true
        blockingOnly.value = true
    }

    /** Hide (snooze) an alert row until its condition clears, or show it again. */
    fun snooze(key: String, on: Boolean) {
        scope.launch { snoozeStore.set(key, on) }
    }

    /** Follow an alert row's fix; the route leaves for the screen that makes it. */
    fun openFix(fix: ReadinessFix) {
        setOpen(false)
        events.tryEmit(CockpitEvent.OpenFix(fix))
    }
}
