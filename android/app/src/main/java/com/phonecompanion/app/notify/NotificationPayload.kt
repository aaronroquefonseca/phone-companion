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

    fun description(kind: String): String = when (kind) {
        NOTE -> "Notes from your server."
        ALERT -> "Important alerts from your server."
        else -> "All other notifications."
    }
}

/**
 * FCM data-message payload per docs/api-contract.md (`POST /api/v1/notify`
 * fan-out fields, delivered as the message's data map).
 *
 * Wire keys (all strings, FCM-data compatible): `v`, `notification_id`,
 * `kind`, `title`, `body`, `priority`, `channel`, `image_url`,
 * `action_<i>_id` / `action_<i>_title` for i in 0..2 — plus, for secret
 * fan-out, `secret: "true"` with P5 envelopes in `title_env`/`body_env`
 * (decrypted by [CompanionMessagingService] via SecretCrypto; the plain
 * `title`/`body` keys are absent there). Unknown keys are ignored for
 * forward compatibility.
 *
 * Every parsed string is length-bounded at parse time ([take] caps): a
 * compromised or buggy server must not be able to blow up rendering memory
 * with a multi-megabyte data value.
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
    /** True when title/body arrive as P5 envelopes (see [titleEnvelope]). */
    val isSecret: Boolean,
    /** P5 envelope JSON for the title, or null when absent/not secret. */
    val titleEnvelope: String?,
    /** P5 envelope JSON for the body, or null when absent/not secret. */
    val bodyEnvelope: String?,
) {
    val isKnownKind: Boolean get() = NotificationKinds.isKnown(kind)

    /**
     * Channel behavior: an explicit backend `channel` wins when it matches
     * [CHANNEL_REGEX] (lowercased, max 64 chars); anything else fails
     * closed to the kind channel. Kind channels are allowlisted: known
     * kinds get `pc_<kind>`, every unknown kind shares `pc_general` — a
     * novel kind must never mint an unbounded set of channels. Per-kind
     * channels keep categories independently mutable by the user
     * (sound/importance per kind). Android keeps a channel's user settings
     * once created, so a later priority change only affects newly created
     * channels — never rewrite an existing channel's importance here.
     */
    fun channelId(): String {
        val explicit = channel?.trim()?.lowercase()?.take(MAX_CHANNEL_CHARS)
        if (!explicit.isNullOrEmpty() && CHANNEL_REGEX.matches(explicit)) return explicit
        if (isKnownKind) return sanitize("pc_$kind").ifEmpty { GENERAL_CHANNEL_ID }
        return GENERAL_CHANNEL_ID
    }

    /**
     * Legacy channel to delete, if any: pre-allowlist builds created one
     * channel per kind (`pc_<kind>`) even for unknown kinds. When the kind
     * is unknown and no valid explicit channel applies, that legacy
     * channel is dead — the notification now posts to [GENERAL_CHANNEL_ID].
     * Returns null when there is nothing to clean up. Explicit channels
     * are never touched here.
     */
    fun legacyChannelIdForCleanup(): String? {
        if (isKnownKind) return null
        val explicit = channel?.trim()?.lowercase()?.take(MAX_CHANNEL_CHARS)
        if (!explicit.isNullOrEmpty() && CHANNEL_REGEX.matches(explicit)) return null
        val legacy = sanitize("pc_$kind").ifEmpty { GENERAL_CHANNEL_ID }
        return if (legacy != GENERAL_CHANNEL_ID) legacy else null
    }

    fun channelName(): String = NotificationKinds.displayName(kind)

    fun channelDescription(): String = NotificationKinds.description(kind)

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

    /**
     * Display title with the blank-notification fallback applied: a blank
     * title (including undecryptable secrets, which arrive title-less)
     * renders as [fallback] (the app name), never as empty chrome.
     */
    fun displayTitle(fallback: String): String = title.ifBlank { fallback }

    /** Display body: null when blank so the caller posts text-only chrome. */
    fun displayBody(): String? = body.ifBlank { null }

    /** Stable int id so re-deliveries update rather than stack. */
    fun notifyId(): Int = (notificationId ?: "$kind:$title:$body").hashCode()

    companion object {
        /** Fallback channel for unknown kinds (channel allowlist). */
        const val GENERAL_CHANNEL_ID = "pc_general"

        /** Explicit channels must match this after lowercasing. */
        val CHANNEL_REGEX = Regex("^[a-z0-9][a-z0-9_\\-]{0,63}$")

        /** Max actions honored per notification (contract cap). */
        const val MAX_ACTIONS = 3

        /** Parse-time length bounds (chars). */
        const val MAX_TITLE_CHARS = 1000
        const val MAX_BODY_CHARS = 8192
        const val MAX_KIND_CHARS = 64
        const val MAX_CHANNEL_CHARS = 64
        const val MAX_NOTIFICATION_ID_CHARS = 128
        const val MAX_ENVELOPE_CHARS = 65536
        const val MAX_ACTION_ID_CHARS = 64
        const val MAX_ACTION_TITLE_CHARS = 200

        fun from(message: RemoteMessage): NotificationPayload = fromMap(message.data)

        fun fromMap(data: Map<String, String>): NotificationPayload {
            val kind = data["kind"]?.trim()?.lowercase()?.take(MAX_KIND_CHARS)?.ifEmpty { null }
                ?: NotificationKinds.NOTE
            val secret = data["secret"]?.trim().equals("true", ignoreCase = true)
            return NotificationPayload(
                kind = kind,
                title = data["title"].orEmpty().take(MAX_TITLE_CHARS),
                body = data["body"].orEmpty().take(MAX_BODY_CHARS),
                channel = data["channel"]?.ifBlank { null }?.take(MAX_CHANNEL_CHARS),
                priority = data["priority"]?.trim()?.take(16)?.ifEmpty { null } ?: "default",
                notificationId = data["notification_id"]?.take(MAX_NOTIFICATION_ID_CHARS),
                version = data["v"]?.trim()?.take(16)?.ifEmpty { null }
                    ?: NotificationKinds.PAYLOAD_VERSION,
                actions = parseActions(data),
                imageUrl = sanitizeImageUrl(data["image_url"]),
                isSecret = secret,
                titleEnvelope = data["title_env"]?.take(MAX_ENVELOPE_CHARS),
                bodyEnvelope = data["body_env"]?.take(MAX_ENVELOPE_CHARS),
            )
        }

        private fun parseActions(data: Map<String, String>): List<NotificationAction> {
            val out = mutableListOf<NotificationAction>()
            for (i in 0 until MAX_ACTIONS) {
                val id = data["action_${i}_id"]?.trim()?.take(MAX_ACTION_ID_CHARS).orEmpty()
                val title = data["action_${i}_title"]?.trim()?.take(MAX_ACTION_TITLE_CHARS).orEmpty()
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
