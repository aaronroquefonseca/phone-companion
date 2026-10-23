package com.phonecompanion.app.notify

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM data-message payload per docs/api-contract.md (`POST /api/v1/notify`
 * fan-out fields, delivered as the message's data map).
 *
 * Basic kinds (`note`, `alert`) render with kind-specific channels; unknown
 * `kind` values still render generically (contract extensibility rule) so
 * new kinds never crash old apps. Rich behavior — action buttons, image
 * attachments, per-action intents — is the Wave-2 notifications extension's
 * job; this layer parses and displays title/body with tap-to-open.
 */
data class NotificationPayload(
    val kind: String,
    val title: String,
    val body: String,
    /** Operator-chosen channel id, if the backend sent one. */
    val channel: String?,
    val priority: String,
    val notificationId: String?,
) {
    val isKnownKind: Boolean get() = kind in BASIC_KINDS

    /**
     * Channel per kind: an explicit backend `channel` wins, otherwise one
     * channel per kind keeps categories independently mutable by the user.
     */
    fun channelId(): String {
        val explicit = channel?.trim()
        if (!explicit.isNullOrEmpty()) return sanitize(explicit)
        return "pc_$kind"
    }

    fun channelName(): String =
        if (isKnownKind) kind.replaceFirstChar { it.uppercase() } + " notifications"
        else "General notifications"

    fun importance(): Int = when (priority.lowercase()) {
        "min" -> NotificationManager.IMPORTANCE_MIN
        "low" -> NotificationManager.IMPORTANCE_LOW
        "high" -> NotificationManager.IMPORTANCE_HIGH
        "max" -> NotificationManager.IMPORTANCE_MAX
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    fun compatPriority(): Int = when (priority.lowercase()) {
        "min" -> NotificationCompat.PRIORITY_MIN
        "low" -> NotificationCompat.PRIORITY_LOW
        "high" -> NotificationCompat.PRIORITY_HIGH
        "max" -> NotificationCompat.PRIORITY_MAX
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    /** Stable int id so re-deliveries update rather than stack. */
    fun notifyId(): Int = (notificationId ?: "$kind:$title:$body").hashCode()

    companion object {
        const val KIND_NOTE = "note"
        const val KIND_ALERT = "alert"
        val BASIC_KINDS: Set<String> = setOf(KIND_NOTE, KIND_ALERT)

        fun from(message: RemoteMessage): NotificationPayload {
            val data = message.data
            val notification = message.notification
            return NotificationPayload(
                kind = data["kind"]?.trim()?.lowercase()?.ifEmpty { null } ?: KIND_NOTE,
                title = data["title"] ?: notification?.title.orEmpty(),
                body = data["body"] ?: notification?.body.orEmpty(),
                channel = data["channel"]?.ifBlank { null },
                priority = data["priority"]?.trim()?.ifEmpty { null } ?: "default",
                notificationId = data["notification_id"] ?: message.messageId,
            )
        }

        private fun sanitize(id: String): String =
            id.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(64).ifEmpty { "pc_general" }
    }
}
