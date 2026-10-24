package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetJson
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.work.PollStatus

/**
 * Pure text mapping for the widget list screen.
 *
 * Framework-free so the row/status rendering is JVM-testable; [WidgetsActivity]
 * only binds these strings to TextViews. Every cached row shows
 * id/type/title/updated_at plus a payload-shape health marker, so a
 * `getAll()` list that looks populated while every per-type Glance query
 * falls back (unknown types, unparseable payloads) reads as a data problem
 * instead of a render bug. The poll status line always shows the last poll
 * outcome — never a silent empty state.
 */
object WidgetListUi {

    /** Payload-shape health vocabulary (stable, operator-facing). */
    const val HEALTH_OK = "ok"
    const val HEALTH_UNPARSEABLE = "unparseable"
    const val HEALTH_ENCRYPTED_SHAPE = "encrypted-shape"
    const val HEALTH_UNKNOWN_TYPE = "unknown-type"

    /** One cached row: id, type, title, and updated_at. */
    fun rowText(id: String, type: String, title: String, updatedAt: String): String =
        "$id • $type • $title • $updatedAt"

    /** Row texts for every cached widget, in the DAO's id order. */
    fun rowTexts(entities: List<WidgetCacheEntity>): List<String> =
        entities.map { rowText(it.id, it.type, it.title, it.updatedAt) + " • " + payloadHealth(it) }

    /**
     * Payload-shape health for one cached row: can this row's type parse
     * its payload? Shape only — never values, never a decrypt attempt
     * (secret rows check the envelope field names, not the ciphertext).
     */
    fun payloadHealth(entity: WidgetCacheEntity): String = when (entity.type) {
        "text" ->
            if (WidgetPayloadParser.parseTextBody(entity.payloadJson) != null) HEALTH_OK
            else HEALTH_UNPARSEABLE
        "list" ->
            if (WidgetPayloadParser.parseListItems(entity.payloadJson) != null) HEALTH_OK
            else HEALTH_UNPARSEABLE
        "status" ->
            if (WidgetPayloadParser.parseStatus(entity.payloadJson) != null) HEALTH_OK
            else HEALTH_UNPARSEABLE
        "secret" ->
            if (looksLikeSecretEnvelope(entity.payloadJson)) HEALTH_ENCRYPTED_SHAPE
            else HEALTH_UNPARSEABLE
        else -> HEALTH_UNKNOWN_TYPE
    }

    /**
     * One home-screen instance line for the assignment UI: the human label
     * (type + chosen logical widget title or "Not associated") plus the
     * `was-<old id>` re-point hint when the binding pins a gone widget.
     * Pure and JVM-testable. Raw Glance instance ids never appear here —
     * tapping the line opens the same chooser as the configuration
     * activity ([WidgetConfigActivity]).
     */
    fun instanceLabel(
        providerLabel: String,
        rows: List<WidgetCacheEntity>,
        bindingWidgetId: String?,
        staleHint: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (rows.isEmpty()) return "$providerLabel — No cached rows yet"
        val selected = rows.find { it.id == bindingWidgetId }
        if (selected == null) {
            val base = "$providerLabel — Not associated"
            return if (staleHint != null) {
                "$base ($staleHint — gone, pick again)"
            } else {
                base
            }
        }
        val title = com.phonecompanion.app.widgets.WidgetContent.nonBlank(selected.title, "Untitled")
        return "$providerLabel — $title · updated ${humanUpdatedAt(selected.updatedAt, nowMs)}"
    }

    /**
     * Human updated-at for chooser/instance lines ("just now",
     * "5 minutes ago", "3 hours ago", "2 days ago", else the calendar
     * date). Unparseable and future inputs pass through unchanged; never
     * throws.
     */
    fun humanUpdatedAt(iso: String, nowMs: Long = System.currentTimeMillis()): String {
        val then = try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            return iso
        }
        val diffMs = nowMs - then
        if (diffMs < 0) return iso
        val minutes = diffMs / 60_000
        if (minutes < 1) return "just now"
        if (minutes < 60) return "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
        val hours = minutes / 60
        if (hours < 24) return "$hours ${if (hours == 1L) "hour" else "hours"} ago"
        val days = hours / 24
        if (days < 30) return "$days ${if (days == 1L) "day" else "days"} ago"
        return iso.take(10)
    }

    /** Envelope field-name gate (no decrypt, no values, no oracle). */
    private fun looksLikeSecretEnvelope(payloadJson: String): Boolean {
        val obj = WidgetJson.fromJson(payloadJson) ?: return false
        return obj.containsKey("alg") && obj.containsKey("kid") &&
            obj.containsKey("nonce") && obj.containsKey("ciphertext")
    }

    /** Last-poll status line, or a never-polled marker before the first poll. */
    fun statusText(status: PollStatus?, revokedAtMs: Long? = null): String =
        RevocationCopy.pollStatusLine(status, revokedAtMs)

    /** Empty-cache line: names the empty state AND the last poll outcome. */
    fun emptyStateText(status: PollStatus?, revokedAtMs: Long? = null): String =
        "No cached widgets — ${statusText(status, revokedAtMs)}."
}
