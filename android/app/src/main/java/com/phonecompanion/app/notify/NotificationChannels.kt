package com.phonecompanion.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Single owner of the notification channels this app uses for the
 * remote-install flow.
 *
 * The install flow used to post to [NotificationPayload.GENERAL_CHANNEL_ID]
 * — the same user-mutable channel as generic pushes. A user silencing that
 * channel (or a MIN-importance creation from an earlier generic payload)
 * then silently suppressed every install notification, with no in-app
 * record to fall back to. The install flow now has its OWN dedicated,
 * always-DEFAULT-importance channel so its visibility is independent of
 * the generic push channel's user settings.
 */
object NotificationChannels {

    /** Single owner of the shared channel id (mirrors the payload registry). */
    const val GENERAL_CHANNEL_ID = NotificationPayload.GENERAL_CHANNEL_ID

    /** Dedicated channel for the remote-APK install flow. */
    const val INSTALL_CHANNEL_ID = "pc_installs"

    /** Ensures the shared general channel exists. Never throws. */
    fun ensureGeneral(manager: NotificationManager) {
        try {
            if (manager.getNotificationChannel(GENERAL_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                GENERAL_CHANNEL_ID,
                NotificationKinds.displayName("unknown"),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            channel.description = NotificationKinds.description("unknown")
            manager.createNotificationChannel(channel)
        } catch (_: Exception) {
            // Best-effort: posting to a missing channel drops on API 26+,
            // but channel creation must never crash the push path.
        }
    }

    /**
     * Ensures the dedicated install channel exists at DEFAULT importance.
     *
     * IMPORTANCE_DEFAULT is chosen deliberately: the progress post needs
     * to be visible on the status bar without the sound/heads-up noise of
     * HIGH, and the terminal post relies on it staying visible. Existing
     * channels are never downgraded — the user owns their settings.
     * Never throws.
     */
    fun ensureInstalls(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            if (manager.getNotificationChannel(INSTALL_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                INSTALL_CHANNEL_ID,
                "App installs",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            channel.description = "Downloads and installs pushed from your server."
            manager.createNotificationChannel(channel)
        } catch (_: Exception) {
            // Best-effort: never crash the install flow on channel creation.
        }
    }

    /** True when the install channel is absent or blocked by the user. */
    fun installChannelBlocked(context: Context): Boolean = try {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(INSTALL_CHANNEL_ID)
        channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    } catch (_: Exception) {
        false
    }
}
