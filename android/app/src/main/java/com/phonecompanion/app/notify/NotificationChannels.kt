package com.phonecompanion.app.notify

import android.app.NotificationManager

/**
 * Single owner of the shared general notification channel.
 *
 * Both the generic push path ([CompanionMessagingService]) and the
 * remote-APK-install flow post to [NotificationPayload.GENERAL_CHANNEL_ID]
 * (the install flow is a dedicated flow, never a generic kind, so it
 * mints no per-kind channel). One function so the id, name, and
 * description can never disagree between the two callers.
 */
object NotificationChannels {

    /** Single owner of the shared channel id (mirrors the payload registry). */
    const val GENERAL_CHANNEL_ID = NotificationPayload.GENERAL_CHANNEL_ID

    /** Ensures the shared general channel exists. Never throws. */
    fun ensureGeneral(manager: NotificationManager) {
        try {
            if (manager.getNotificationChannel(GENERAL_CHANNEL_ID) != null) return
            val channel = android.app.NotificationChannel(
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
}
