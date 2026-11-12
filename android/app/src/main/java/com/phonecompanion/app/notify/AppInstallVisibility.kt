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
 * makes [NotificationManager.notify] a silent no-op, and a user-blocked
 * channel does the same on every version. The install flow must detect that
 * and surface its progress in-app instead, so there is always exactly one
 * visible source of truth (the notification when it can post, an in-app
 * indicator otherwise).
 */
object AppInstallVisibility {

    /**
     * Whether the status-bar notification can be the install flow's source
     * of truth. False when POST_NOTIFICATIONS is denied (API 33+) or the
     * shared general channel is blocked by the user. Pure ([sdkInt] and the
     * two platform flags injected) so it is unit-testable.
     */
    fun canPostNotifications(
        sdkInt: Int,
        notificationsGranted: Boolean,
        channelBlocked: Boolean,
    ): Boolean = if (sdkInt >= 33) notificationsGranted && !channelBlocked else !channelBlocked

    /** Android edge: reads the live platform state for [canPostNotifications]. */
    fun canPostNotifications(context: Context): Boolean {
        val app = context.applicationContext
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val blocked = runCatching {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.getNotificationChannel(NotificationChannels.GENERAL_CHANNEL_ID)
                ?.importance == NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)
        return canPostNotifications(Build.VERSION.SDK_INT, granted, blocked)
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
