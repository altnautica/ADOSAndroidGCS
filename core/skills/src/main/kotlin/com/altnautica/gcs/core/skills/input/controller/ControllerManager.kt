package com.altnautica.gcs.core.skills.input.controller

import android.content.Context
import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Attached game controllers and their hardware: battery, rumble and the light bar.
 * Devices are identified by vendor and product id only. Rumble and the light bar are
 * best effort; no safety alert relies on them.
 */
@Singleton
class ControllerManager internal constructor(
    router: AdosInputRouter,
    settings: Flow<ControllerSettings>,
    scope: CoroutineScope,
    private val readDevices: () -> List<ControllerInfo>,
    private val platform: PlatformControllers?,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        router: AdosInputRouter,
        settingsRepository: ControllerSettingsRepository,
        @InputScope scope: CoroutineScope,
    ) : this(router, settingsRepository.settings, scope, PlatformControllers(context))

    private constructor(
        router: AdosInputRouter,
        settings: Flow<ControllerSettings>,
        scope: CoroutineScope,
        platform: PlatformControllers,
    ) : this(router, settings, scope, platform::read, platform)

    private val _controllers = MutableStateFlow(readDevices())

    @Volatile
    private var current = ControllerSettings()

    /** Every attached physical gamepad or joystick. */
    val controllers: StateFlow<List<ControllerInfo>> = _controllers.asStateFlow()

    /** The controller driving input (the router's active device), otherwise the first attached. */
    val active: StateFlow<ControllerInfo?> = combine(_controllers, router.activeDeviceId) { list, id -> pick(list, id) }
        .stateIn(scope, SharingStarted.Eagerly, pick(_controllers.value, router.activeDeviceId.value))

    init {
        scope.launch {
            settings.collect { s ->
                current = s
                router.extraDeadZone = s.deadZonePct / 100f
                if (!s.lightbar) platform?.clearLights()
            }
        }
        platform?.listen(::refresh)
        scope.launch {
            // Battery levels change without a device event on most controllers.
            while (isActive) {
                delay(BATTERY_POLL_MS)
                refresh()
            }
        }
    }

    /** Re-reads the attached controllers and their battery state. */
    fun refresh() {
        val list = readDevices()
        platform?.retain(list.mapTo(HashSet()) { it.deviceId })
        _controllers.value = list
    }

    /** Rumbles the active controller; silent when rumble is off or unsupported. */
    fun rumble(pattern: Rumble) {
        if (!current.rumble) return
        val id = active.value?.deviceId ?: return
        platform?.rumble(id, pattern)
    }

    /** Colours the active controller's light bar; null restores its default. No-op when the light bar setting is off. */
    fun setLightbar(argb: Int?) {
        if (!current.lightbar) return
        val id = active.value?.deviceId ?: return
        platform?.setLightbar(id, argb)
    }

    private fun pick(list: List<ControllerInfo>, activeId: Int?): ControllerInfo? =
        list.firstOrNull { it.deviceId == activeId } ?: list.firstOrNull()

    private companion object {
        const val BATTERY_POLL_MS = 30_000L
    }
}
