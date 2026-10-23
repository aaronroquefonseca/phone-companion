package com.phonecompanion.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.phonecompanion.app.R
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.ui.MainActivity

/**
 * FCM receiver: renders data messages per [NotificationPayload] — one
 * channel per kind, tap opens [MainActivity]. Unknown kinds render
 * generically (never crash). Action buttons and image attachments are
 * Wave-2 (P9) scope and are ignored here.
 */
class CompanionMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = NotificationPayload.from(message)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager, payload)

        val tap = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_TITLE, payload.title)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_BODY, payload.body)
        val tapIntent = PendingIntent.getActivity(
            this,
            payload.notifyId(),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, payload.channelId())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.title.ifBlank { getString(R.string.app_name) })
            .setContentText(payload.body.ifBlank { null })
            .setStyle(
                if (payload.body.isNotBlank()) {
                    NotificationCompat.BigTextStyle().bigText(payload.body)
                } else {
                    null
                },
            )
            .setPriority(payload.compatPriority())
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(payload.notifyId(), notification)
    }

    override fun onNewToken(token: String) {
        // Cached for the next enroll; already-paired devices re-send it on
        // the next widgets poll (widgets PR) — never logged.
        SecureStorage.getInstance(this).fcmToken = token
    }

    private fun ensureChannel(manager: NotificationManager, payload: NotificationPayload) {
        if (manager.getNotificationChannel(payload.channelId()) != null) return
        manager.createNotificationChannel(
            NotificationChannel(payload.channelId(), payload.channelName(), payload.importance()),
        )
    }
}
