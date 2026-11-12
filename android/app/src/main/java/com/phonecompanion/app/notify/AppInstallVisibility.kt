package com.phonecompanion.app.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source-of-truth decision for the install flow's visible progress:
 * the status-bar notification, or an in-app indicator when the notification
 * cannot show.
 *
 * On Android 13+ a denied [android.Manifest.permission.POST_NOTIFICATIONS]
 * makes [NotificationManager.notify] a silent no-op; a user-blocked
 * channel does the same on every version, and an IMPORTANCE_MIN channel
 * renders posts invisibly (no status-bar icon). The install flow must
 * detect any of those and surface its progress in-app instead, so there
 * is always exactly one visible source of truth.
 */
object AppInstallVisibility {

    /**
     * Whether the status-bar notification can be the install flow's source
     * of truth. False when the app's notifications are disabled, when
     * POST_NOTIFICATIONS is denied (API 33+), or when the install channel
     * is blocked or MIN-importance (invisible). Pure flags injected so it
     * is unit-testable.
     */
    fun canPostNotifications(
        sdkInt: Int,
        notificationsGranted: Boolean,
        channelBlocked: Boolean,
        channelMin: Boolean = false,
        appNotificationsEnabled: Boolean = true,
    ): Boolean {
        if (!appNotificationsEnabled) return false
        if (channelBlocked || channelMin) return false
        return if (sdkInt >= 33) notificationsGranted else true
    }

    /** Android edge: reads the live platform state for [canPostNotifications]. */
    fun canPostNotifications(context: Context): Boolean {
        val app = context.applicationContext
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val manager = runCatching {
            app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }.getOrNull()
        val enabled = runCatching { manager?.areNotificationsEnabled() ?: true }
            .getOrDefault(true)
        val channel = runCatching {
            manager?.getNotificationChannel(NotificationChannels.INSTALL_CHANNEL_ID)
        }.getOrNull()
        val blocked = channel?.importance == NotificationManager.IMPORTANCE_NONE
        val min = channel?.importance == NotificationManager.IMPORTANCE_MIN
        return canPostNotifications(
            Build.VERSION.SDK_INT,
            granted,
            blocked,
            min,
            enabled,
        )
    }
}

/**
 * Process-wide registry of in-flight install downloads (the in-app half of
 * the visibility guarantee). The detached auto-download and the foreground
 * pre-install flow both publish their progress here; the pre-install screen
 * and the Widgets screen read it so a download is visible even when the
 * status-bar notification cannot post.
 *
 * Keyed by push id (the file/flow identity). Pure JVM (zero Android
 * imports) so the registry is unit-testable without an emulator.
 */
object AppInstallProgress {

    /** One in-flight download (progress-only — no URLs, hashes, or tokens). */
    data class Entry(
        val pushId: String,
        val packageName: String,
        val label: String,
        /** Percent 0..100, or null when the total length is unknown. */
        val percent: Int?,
    )

    private val active = ConcurrentHashMap<String, Entry>()

    fun set(pushId: String, packageName: String, label: String, percent: Int?) {
        active[pushId] = Entry(pushId, packageName, label, percent)
    }

    fun clear(pushId: String) {
        active.remove(pushId)
    }

    fun get(pushId: String): Entry? = active[pushId]

    fun active(): List<Entry> = active.values.toList()

    /** Test-only reset. Production never clears (see [AppInstallSessions]). */
    fun clearForTest() {
        active.clear()
    }
}
