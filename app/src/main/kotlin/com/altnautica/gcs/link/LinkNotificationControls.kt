package com.altnautica.gcs.link

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.cockpit.NotificationControls
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** A stop control on the link notification: its intent action, the built-in it runs and its label key. */
enum class StopAction(val intentAction: String, val builtinId: String, val labelKey: String) {
    Pause("com.altnautica.gcs.link.PAUSE", BuiltinIds.PAUSE, "shell.notify.pause"),
    Land("com.altnautica.gcs.link.LAND", BuiltinIds.LAND, "shell.notify.land"),
    Return("com.altnautica.gcs.link.RETURN", BuiltinIds.RTH, "shell.notify.return"),
    ;

    companion object {
        fun of(intentAction: String?): StopAction? = entries.firstOrNull { it.intentAction == intentAction }
    }
}

/**
 * The link notification as a stop control. [posted] is true while [LinkService] shows it in
 * the foreground with Pause, Land and Return. It is reachable while posted and shown, and,
 * with the phone locked, only when the lock screen shows it. The lock-screen reading is
 * cached for a second: the no-RC heartbeat asks once a tick.
 */
@Singleton
class LinkNotificationControls @Inject constructor(@ApplicationContext private val context: Context) : NotificationControls {
    @Volatile var posted: Boolean = false

    @Volatile private var hiddenAtMs: Long? = null
    @Volatile private var hidden = false

    override fun reachable(): Boolean {
        if (!posted || !shown()) return false
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return !locked || !lockScreenHidden()
    }

    override fun lockScreenHidden(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val at = hiddenAtMs
        if (at == null || now - at >= CACHE_MS) {
            hidden = readLockScreenHidden()
            hiddenAtMs = now
        }
        return hidden
    }

    /** The app may post notifications and the link channel is not switched off. */
    private fun shown(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(LinkService.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun readLockScreenHidden(): Boolean {
        val resolver = context.contentResolver
        if (secureInt(resolver, LOCK_SCREEN_SHOW_NOTIFICATIONS) == 0) return true
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(LinkService.CHANNEL_ID) ?: return false
        if (channel.lockscreenVisibility == Notification.VISIBILITY_SECRET) return true
        // The link channel is a silent one: hidden where the lock screen hides silent notifications.
        return channel.importance < NotificationManager.IMPORTANCE_DEFAULT && secureInt(resolver, LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS) == 0
    }

    /** A lock-screen setting, or null where the platform does not let this app read it. */
    private fun secureInt(resolver: ContentResolver, key: String): Int? =
        runCatching { Settings.Secure.getInt(resolver, key, 1) }.getOrNull()

    private companion object {
        const val CACHE_MS = 1_000L
        const val LOCK_SCREEN_SHOW_NOTIFICATIONS = "lock_screen_show_notifications"
        const val LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS = "lock_screen_show_silent_notifications"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationControlsModule {
    @Binds
    abstract fun notificationControls(controls: LinkNotificationControls): NotificationControls
}
