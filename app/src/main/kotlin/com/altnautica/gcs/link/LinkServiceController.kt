package com.altnautica.gcs.link

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.altnautica.gcs.core.link.vehicle.OpenLink
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Starts or stops the foreground service. Split out so the policy is testable without Android. */
interface LinkServiceStarter {
    fun start()
    fun stop()
}

class AndroidLinkServiceStarter @Inject constructor(@ApplicationContext private val context: Context) : LinkServiceStarter {
    override fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground-service start while the app is in the background.
            android.util.Log.w("LinkService", "start refused while in background", e)
        }
    }
    override fun stop() {
        context.stopService(Intent(context, LinkService::class.java))
    }
}

/**
 * Keeps [LinkService] running exactly while at least one vehicle link is open:
 * the first link starts it, the last link closing stops it.
 */
@Singleton
class LinkServiceController internal constructor(
    private val openLinks: Flow<List<OpenLink>>,
    private val starter: LinkServiceStarter,
) {
    @Inject
    constructor(vehicles: VehicleRepository, starter: AndroidLinkServiceStarter) : this(vehicles.openLinks, starter)

    fun start(scope: CoroutineScope) {
        scope.launch {
            openLinks.map { it.isNotEmpty() }.distinctUntilChanged().collect { anyOpen ->
                // The first emission is the cold state; with no link there is nothing to stop.
                if (anyOpen) starter.start() else if (started) starter.stop()
                started = anyOpen
            }
        }
    }

    private var started = false
}
