package com.altnautica.gcs

import android.app.Application
import android.util.Log
import com.altnautica.gcs.di.LinkGraphReport
import com.altnautica.gcs.link.FlightLogController
import com.altnautica.gcs.link.LinkServiceController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class AdosApplication : Application() {

    @Inject lateinit var linkServiceController: LinkServiceController

    @Inject lateinit var flightLogController: FlightLogController

    @Inject lateinit var linkGraphReport: dagger.Lazy<LinkGraphReport>

    /** Process-lifetime scope for app-wide observers. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        linkServiceController.start(appScope)
        flightLogController.start(appScope)
        if (BuildConfig.DEBUG) linkGraphReport.get().lines().forEach { Log.i("AdosGraph", it) }
    }
}
