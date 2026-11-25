package com.altnautica.gcs

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.altnautica.gcs.core.agent.net.LanNetworkWatcher
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.di.LinkGraphReport
import com.altnautica.gcs.feature.settings.controller.PadSeenMemory
import com.altnautica.gcs.link.FlightLogController
import com.altnautica.gcs.link.LinkServiceController
import com.altnautica.gcs.link.SelectedLinkHolder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class AdosApplication : Application() {

    @Inject lateinit var linkServiceController: LinkServiceController

    @Inject lateinit var flightLogController: FlightLogController

    @Inject lateinit var linkGraphReport: dagger.Lazy<LinkGraphReport>

    /** Injected here so it watches for pads from process start, not from the first Controls visit. */
    @Inject lateinit var padSeenMemory: PadSeenMemory

    @Inject lateinit var selectedLinkHolder: SelectedLinkHolder

    @Inject lateinit var lanNetworkWatcher: LanNetworkWatcher

    @Inject lateinit var cockpitSpec: dagger.Lazy<CockpitSpecRepository>

    /** Process-lifetime scope for app-wide observers. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        linkServiceController.start(appScope)
        flightLogController.start(appScope)
        lanNetworkWatcher.start()
        val onScreen = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
        selectedLinkHolder.start(appScope, onScreen)
        warmCockpitSpec()
        if (BuildConfig.DEBUG) linkGraphReport.get().lines().forEach { Log.i("AdosGraph", it) }
    }

    /** Parses the cockpit spec off the main thread, so the first cockpit entry does not. */
    private fun warmCockpitSpec() {
        appScope.launch(Dispatchers.Default) {
            runCatching {
                cockpitSpec.get().run {
                    layout
                    layers
                    views
                    setupStrings
                    alertGroups
                    flightState
                    tokens
                    builtins
                    tips
                }
            }.onFailure { Log.w("AdosApp", "cockpit spec warm-up failed", it) }
        }
    }
}
