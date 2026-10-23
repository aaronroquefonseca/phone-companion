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
 * channel per kind (or explicit backend channel), tap opens [MainActivity],
 * up to 3 action buttons re-open the app with the tapped action id, and an
 * `image_url` attachment renders BigPictureStyle on successful HTTPS
 * download. Unknown kinds and unknown payload versions render generically
 * (never crash); image download failures fall back to text (never crash).
 */
class CompanionMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = NotificationPayload.from(message)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager, payload)

        val tapIntent = PendingIntent.getActivity(
            this,
            payload.notifyId(),
            appIntent(payload, actionId = null),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, payload.channelId())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.title.ifBlank { getString(R.string.app_name) })
            .setContentText(payload.body.ifBlank { null })
            .setPriority(payload.compatPriority())
            .setContentIntent(tapIntent)
            .setAutoCancel(true)

        // Action buttons (contract: max 3). Each opens the app with the
        // notification + action ids; action taps do not auto-dismiss, so
        // MainActivity cancels the notification id on open.
        payload.actions.forEachIndexed { index, action ->
            val actionIntent = PendingIntent.getActivity(
                this,
                actionRequestCode(payload.notifyId(), index),
                appIntent(payload, actionId = action.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, action.title, actionIntent)
        }

        // Image attachment: BigPictureStyle on success, BigTextStyle
        // fallback on any download/decode failure (fail-closed).
        val bitmap = payload.imageUrl?.let(NotificationImages::download)
        if (bitmap != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setSummaryText(payload.body.ifBlank { null }),
            )
        } else if (payload.body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
        }

        manager.notify(payload.notifyId(), builder.build())
    }

    override fun onNewToken(token: String) {
        // Cached for the next enroll; already-paired devices re-send it on
        // the next widgets poll (widgets PR) — never logged.
        SecureStorage.getInstance(this).fcmToken = token
    }

    private fun appIntent(payload: NotificationPayload, actionId: String?): Intent =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_TITLE, payload.title)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_BODY, payload.body)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, payload.notificationId)
            .putExtra(MainActivity.EXTRA_NOTIFY_INT_ID, payload.notifyId())
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ACTION_ID, actionId)

    private fun ensureChannel(manager: NotificationManager, payload: NotificationPayload) {
        if (manager.getNotificationChannel(payload.channelId()) != null) return
        manager.createNotificationChannel(
            NotificationChannel(payload.channelId(), payload.channelName(), payload.importance()),
        )
    }

    companion object {
        /** Distinct request codes per (notification, action) pair. */
        fun actionRequestCode(notifyId: Int, index: Int): Int = notifyId * 4 + index
    }
}
