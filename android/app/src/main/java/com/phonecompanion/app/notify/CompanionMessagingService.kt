package com.phonecompanion.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.phonecompanion.app.R
import com.phonecompanion.app.data.FcmTokenBackfill
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.ui.MainActivity
import com.phonecompanion.app.widgets.SecretCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM receiver: renders data messages per [NotificationPayload] — one
 * channel per known kind (or explicit backend channel, else pc_general),
 * tap opens [MainActivity], up to 3 action buttons re-open the app with
 * the tapped action id.
 *
 * Posting never waits on the network: the text notification posts FIRST
 * and an `image_url` attachment upgrades it to BigPictureStyle after the
 * fetch completes (same id, so the upgrade replaces rather than stacks).
 * Unknown kinds and unknown payload versions render generically (never
 * crash); image download failures keep the text post (never crash).
 *
 * Secret fan-out (`secret: "true"` with `title_env`/`body_env` P5
 * envelopes) decrypts per-device via [SecretCrypto] against the stored
 * device key. ANY decrypt failure renders the existing blank-notification
 * fallback (app-name title, no body) — the single needs-refresh outcome
 * with no oracle: a rotated key is indistinguishable from a tampered
 * envelope on screen and in logcat. Envelopes are never logged and never
 * forwarded into activity extras.
 */
class CompanionMessagingService : FirebaseMessagingService() {

    /**
     * FCM token rotation: persist immediately, then upload when paired so
     * the server row tracks the live token (enroll carries the token too,
     * but rotation happens outside pairing). Best-effort: any failure —
     * including the service dying mid-upload — heals on the next widgets
     * poll via [FcmTokenBackfill.maybeBackfill]. Never throws.
     */
    override fun onNewToken(token: String) {
        try {
            val storage = SecureStorage.getInstance(this)
            storage.fcmToken = token
            if (storage.baseUrl.isNullOrBlank() || storage.deviceToken.isNullOrBlank()) return
            backfillScope.launch {
                try {
                    FcmTokenBackfill.maybeBackfill(this@CompanionMessagingService)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best-effort: the next widgets poll retries.
                }
            }
        } catch (_: Exception) {
            // Token rotation must never crash the push receiver.
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            onMessageReceivedInternal(message)
        } catch (_: Throwable) {
            // Last-resort chrome: a blank fallback post must never itself
            // crash (OOM and framework errors included).
            try {
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                // Text-first fallback still needs its channel on API 26+:
                // posting to a missing channel drops the notification, so
                // ensure the GENERAL fallback channel before notify.
                ensureGeneralChannel(manager)
                manager.notify(
                    (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                    NotificationCompat.Builder(this, NotificationPayload.GENERAL_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(getString(R.string.app_name))
                        .setAutoCancel(true)
                        .build(),
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun onMessageReceivedInternal(message: RemoteMessage) {
        val payload = NotificationPayload.from(message)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager, payload)
        cleanupLegacyChannel(manager, payload)

        val (title, body) = resolveText(payload)
        // Re-key the resolved text through the payload so display
        // fallbacks (blank title → app name, blank body → null) apply
        // uniformly to plain and decrypted-secret text.
        val shown = payload.copy(title = title, body = body)
        val shownTitle = shown.displayTitle(getString(R.string.app_name))

        val tapIntent = PendingIntent.getActivity(
            this,
            tapRequestCode(payload.notifyId()),
            appIntent(payload, title, body, actionId = null),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, payload.channelId())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownTitle)
            .setContentText(shown.displayBody())
            .setPriority(payload.compatPriority())
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            // The BigPicture upgrade below re-notifies the same id: alert
            // only for the first (text) post, never twice for one message.
            .setOnlyAlertOnce(true)

        // Action buttons (contract: max 3). Each opens the app with the
        // notification + action ids; action taps do not auto-dismiss, so
        // MainActivity cancels the notification id on open.
        payload.actions.forEach { action ->
            val actionIntent = PendingIntent.getActivity(
                this,
                actionRequestCode(payload.notifyId(), action.id),
                appIntent(payload, title, body, actionId = action.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, action.title, actionIntent)
        }

        if (body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        // Post FIRST: the user sees text immediately even if the image
        // fetch stalls or fails. No network on the critical path.
        manager.notify(payload.notifyId(), builder.build())

        // Upgrade after fetch: BigPictureStyle replaces the text post on
        // success; any failure keeps the text post (fail-closed).
        val bitmap = payload.imageUrl?.let(NotificationImages::download)
        if (bitmap != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setSummaryText(shown.displayBody()),
            )
            manager.notify(payload.notifyId(), builder.build())
        }
    }

    /**
     * Resolves display text: secret payloads decrypt title/body
     * per-device; every failure (missing key, kid mismatch, bad envelope,
     * auth failure) falls back to blank — the existing
     * blank-notification rendering, indistinguishable from a genuinely
     * empty notification. Plaintext envelopes are never logged.
     */
    private fun resolveText(payload: NotificationPayload): Pair<String, String> {
        if (!payload.isSecret) return payload.title to payload.body
        val storage = SecureStorage.getInstance(this)
        val title = SecretCrypto.decryptNotifyField(
            envelopeJson = payload.titleEnvelope,
            deviceKeyBase64 = storage.deviceKey,
            expectedKid = storage.deviceId,
            notificationId = payload.notificationId,
            field = "title",
        )?.take(NotificationPayload.MAX_TITLE_CHARS).orEmpty()
        val body = SecretCrypto.decryptNotifyField(
            envelopeJson = payload.bodyEnvelope,
            deviceKeyBase64 = storage.deviceKey,
            expectedKid = storage.deviceId,
            notificationId = payload.notificationId,
            field = "body",
        )?.take(NotificationPayload.MAX_BODY_CHARS).orEmpty()
        return title to body
    }

    private fun appIntent(
        payload: NotificationPayload,
        title: String,
        body: String,
        actionId: String?,
    ): Intent =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_TITLE, title)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_BODY, body)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, payload.notificationId)
            .putExtra(MainActivity.EXTRA_NOTIFY_INT_ID, payload.notifyId())
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ACTION_ID, actionId)

    private fun ensureChannel(manager: NotificationManager, payload: NotificationPayload) {
        if (manager.getNotificationChannel(payload.channelId()) != null) return
        val channel = NotificationChannel(payload.channelId(), payload.channelName(), payload.importance())
        channel.description = payload.channelDescription()
        manager.createNotificationChannel(channel)
    }

    /**
     * Ensures the GENERAL fallback channel for the last-resort blank post.
     * Best-effort: never throws (the fallback notify below must not fail).
     */
    private fun ensureGeneralChannel(manager: NotificationManager) {
        try {
            if (manager.getNotificationChannel(NotificationPayload.GENERAL_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                NotificationPayload.GENERAL_CHANNEL_ID,
                "General notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            channel.description = "All other notifications."
            manager.createNotificationChannel(channel)
        } catch (_: Throwable) {
        }
    }

    /**
     * Deletes the pre-allowlist per-kind channel for unknown kinds (older
     * builds minted `pc_<kind>` for any kind). Explicit channels are never
     * deleted; known-kind channels are never deleted.
     */
    private fun cleanupLegacyChannel(manager: NotificationManager, payload: NotificationPayload) {
        val legacy = payload.legacyChannelIdForCleanup() ?: return
        try {
            manager.deleteNotificationChannel(legacy)
        } catch (_: Throwable) {
        }
    }

    companion object {
        /**
         * Stable, collision-resistant request codes (finding 26).
         *
         * The old `notifyId * 4 + index` scheme collided: an action index
         * could equal another notification's tap code (and indices wrapped
         * across notifications). Codes are now hash-mixed over distinct
         * domains — ("tap", notifyId) vs ("action", notifyId, actionId) —
         * so a tap never equals an action code unless the hashes themselves
         * collide (32-bit, accepted residual risk). Stable across process
         * restarts (String.hashCode, no atomic counter to reset), explicit
         * intents + FLAG_IMMUTABLE are kept. Broadcast-backed actions are
         * out of scope (content/activity intents only here).
         */
        fun tapRequestCode(notifyId: Int): Int = mix("tap", notifyId, null)

        /** Distinct request code per (notification, action) pair. */
        fun actionRequestCode(notifyId: Int, index: Int): Int = mix("action", notifyId, index)

        /**
         * Preferred overload: binds the stable action id (not its position),
         * so reordered actions keep their codes.
         */
        fun actionRequestCode(notifyId: Int, actionId: String): Int =
            31 * mix("action", notifyId, null) + actionId.hashCode()

        private fun mix(domain: String, notifyId: Int, index: Int?): Int {
            var h = domain.hashCode()
            h = 31 * h + notifyId
            h = 31 * h + (index ?: -1)
            return h
        }

        /**
         * Detached upload scope: token backfill must not hold the service
         * alive, and a failed upload must not cancel anything else.
         */
        private val backfillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
