package com.phonecompanion.app.notify

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage

/**
 * One tappable notification action (contract: max 3 per notification).
 * The stable [id] is echoed back in [MainActivity.EXTRA_NOTIFICATION_ACTION_ID]
 * when the user taps the button; [title] is the button label.
 */
data class NotificationAction(
    val id: String,
    val title: String,
)

/**
 * Extensible notification kinds (P9). Known kinds get tailored rendering;
 * unknown kinds render generically (title/body, default channel) so new
 * server-side kinds never require an app update and never crash old apps.
 * Payloads carry a version ("v", currently "1"): readers ignore unknown
 * future keys and render unknown versions generically rather than dropping
 * the notification.
 *
 * To add a kind: add its key to [KNOWN_KINDS] with a display name in
 * [displayName], and — only if it needs more than the generic title/body
 * rendering — branch on it in [CompanionMessagingService]. Nothing else
 * changes: channel, priority, actions, and image handling are kind-agnostic.
 */
object NotificationKinds {
    const val NOTE = "note"
    const val ALERT = "alert"

    /** Payload version this app understands. */
    const val PAYLOAD_VERSION = "1"

    val KNOWN_KINDS: Set<String> = setOf(NOTE, ALERT)

    fun isKnown(kind: String): Boolean = kind in KNOWN_KINDS

    fun displayName(kind: String): String =
        if (isKnown(kind)) kind.replaceFirstChar { it.uppercase() } + " notifications"
        else "General notifications"
}

/**
 * FCM data-message payload per docs/api-contract.md (`POST /api/v1/notify`
 * fan-out fields, delivered as the message's data map).
 *
 * Wire keys (all strings, FCM-data compatible): `v`, `notification_id`,
 * `kind`, `title`, `body`, `priority`, `channel`, `image_url`,
 * `action_<i>_id` / `action_<i>_title` for i in 0..2. Unknown keys are
 * ignored for forward compatibility.
 */
data class NotificationPayload(
    val kind: String,
    val title: String,
    val body: String,
    /** Operator-chosen channel id, if the backend sent one. */
    val channel: String?,
    val priority: String,
    val notificationId: String?,
    /** Payload version ("v"); unknown versions still render generically. */
    val version: String,
    /** Up to 3 actions; empty when the backend sent none. */
    val actions: List<NotificationAction>,
    /** HTTPS attachment URL, or null when absent/invalid (fail-closed). */
    val imageUrl: String?,
) {
    val isKnownKind: Boolean get() = NotificationKinds.isKnown(kind)

    /**
     * Channel behavior: one channel per kind (plus an explicit backend
     * `channel` override, sanitized). Per-kind channels keep categories
     * independently mutable by the user (sound/importance per kind).
     * Android keeps a channel's user settings once created, so a later
     * priority change only affects newly created channels — never rewrite
     * an existing channel's importance here.
     */
    fun channelId(): String {
        val explicit = channel?.trim()
        if (!explicit.isNullOrEmpty()) return sanitize(explicit)
        return sanitize("pc_$kind").ifEmpty { "pc_general" }
    }

    fun channelName(): String = NotificationKinds.displayName(kind)

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
        /** Max actions honored per notification (contract cap). */
        const val MAX_ACTIONS = 3

        fun from(message: RemoteMessage): NotificationPayload = fromMap(message.data)

        fun fromMap(data: Map<String, String>): NotificationPayload {
            val kind = data["kind"]?.trim()?.lowercase()?.ifEmpty { null }
                ?: NotificationKinds.NOTE
            return NotificationPayload(
                kind = kind,
                title = data["title"].orEmpty(),
                body = data["body"].orEmpty(),
                channel = data["channel"]?.ifBlank { null },
                priority = data["priority"]?.trim()?.ifEmpty { null } ?: "default",
                notificationId = data["notification_id"],
                version = data["v"]?.trim()?.ifEmpty { null }
                    ?: NotificationKinds.PAYLOAD_VERSION,
                actions = parseActions(data),
                imageUrl = sanitizeImageUrl(data["image_url"]),
            )
        }

        private fun parseActions(data: Map<String, String>): List<NotificationAction> {
            val out = mutableListOf<NotificationAction>()
            for (i in 0 until MAX_ACTIONS) {
                val id = data["action_${i}_id"]?.trim().orEmpty()
                val title = data["action_${i}_title"]?.trim().orEmpty()
                if (id.isNotEmpty() && title.isNotEmpty()) {
                    out += NotificationAction(id, title)
                }
            }
            return out
        }

        /** HTTPS-only, host required; anything else fails closed to null. */
        private fun sanitizeImageUrl(raw: String?): String? {
            val url = raw?.trim()
            if (url.isNullOrEmpty() || url.length > 2048) return null
            if (!url.regionMatches(0, "https://", 0, "https://".length, ignoreCase = true)) return null
            if (url.substring("https://".length).substringBefore("/").isEmpty()) return null
            return url
        }

        private fun sanitize(id: String): String =
            id.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(64).ifEmpty { "pc_general" }
    }
}
