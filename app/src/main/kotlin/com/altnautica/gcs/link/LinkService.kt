package com.altnautica.gcs.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.altnautica.gcs.MainActivity
import com.altnautica.gcs.R
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.fleet.data.NodeVitals
import com.altnautica.gcs.feature.fleet.data.NodeVitalsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** One open link as the notification shows it. */
data class LinkLine(val id: DeviceId, val name: String, val modeName: String?, val batteryPct: Int?)

/**
 * Foreground service (`connectedDevice`) that runs while any vehicle link is
 * open, so a link survives the app going to the background. Its notification
 * names the node with mode and battery, and offers Disconnect.
 */
@AndroidEntryPoint
class LinkService : LifecycleService() {

    @Inject lateinit var vehicles: VehicleRepository

    @Inject lateinit var nodes: NodeRepository

    @Inject lateinit var vitals: NodeVitalsRepository

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(build(emptyList()))
        lifecycleScope.launch {
            lines().collect { lines ->
                if (lines.isEmpty() && vehicles.openLinks.value.isEmpty()) {
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(lines))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) {
            lifecycleScope.launch {
                vehicles.openLinks.value.forEach { vehicles.disconnect(it.id) }
            }
        }
        return START_NOT_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun lines(): Flow<List<LinkLine>> = combine(vehicles.openLinks, nodes.nodes) { open, known ->
        open.map { link -> known?.firstOrNull { it.id == link.id } ?: unnamed(link.id) }
    }.distinctUntilChanged { a, b -> a.map { it.id to it.reaches } == b.map { it.id to it.reaches } }
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { n -> vitals.vitals(n).onStart { emit(NodeVitals.UNKNOWN) }.map { v -> LinkLine(n.id, n.name, v.modeName, v.batteryPct) } }) {
                    it.toList()
                }
            }
        }
        .distinctUntilChanged()

    private fun unnamed(id: DeviceId) = NodeSummary(
        id, id.value, NodeProfile.Unknown, emptyList(), Liveness.Live, null,
        com.altnautica.gcs.core.model.FcVariant.Unknown, null, null,
    )

    private fun build(lines: List<LinkLine>): Notification {
        val content = LinkNotice.from(lines)
        val title = when (content) {
            is LinkNotice.Single -> content.name
            is LinkNotice.Many -> getString(R.string.link_notification_many, content.count)
            LinkNotice.Starting -> getString(R.string.link_notification_starting)
        }
        val text = when (content) {
            is LinkNotice.Single -> listOf(
                content.modeName ?: getString(R.string.link_notification_mode_unknown),
                content.batteryPct?.let { getString(R.string.link_notification_battery, it) } ?: getString(R.string.link_notification_battery_unknown),
            ).joinToString(" · ")
            is LinkNotice.Many -> content.names.joinToString(", ")
            LinkNotice.Starting -> null
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, LinkService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_link_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.link_notification_disconnect), disconnect)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            // Android 12+ refuses a foreground start from the background; the link keeps running
            // while the app is visible and the service is retried on the next link change.
            Log.w(TAG, "foreground start refused", e)
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "LinkService"
        const val CHANNEL_ID = "vehicle_link"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DISCONNECT = "com.altnautica.gcs.link.DISCONNECT"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.link_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.link_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}

/** What the link notification says, independent of Android resources. */
sealed interface LinkNotice {
    data object Starting : LinkNotice
    data class Single(val name: String, val modeName: String?, val batteryPct: Int?) : LinkNotice
    data class Many(val count: Int, val names: List<String>) : LinkNotice

    companion object {
        fun from(lines: List<LinkLine>): LinkNotice = when (lines.size) {
            0 -> Starting
            1 -> lines[0].let { Single(it.name, it.modeName, it.batteryPct?.takeIf { pct -> pct >= 0 }) }
            else -> Many(lines.size, lines.map { it.name })
        }
    }
}
