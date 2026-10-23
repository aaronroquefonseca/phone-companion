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
     * One home-screen instance line for the assignment UI: the current
     * selection (pinned row title, or the default first row) plus the
     * `was-<old id>` re-point hint when the binding pins a gone widget.
     * Pure and JVM-testable; [WidgetsActivity] binds it to a button that
     * opens the row picker.
     */
    fun instanceLine(
        glanceKey: String,
        rows: List<WidgetCacheEntity>,
        bindingWidgetId: String?,
        staleHint: String?,
    ): String {
        val selected = rows.find { it.id == bindingWidgetId } ?: rows.firstOrNull()
        val selection = when {
            selected == null -> "no cached rows"
            staleHint != null -> "Default (first: ${selected.title}) — $staleHint (gone)"
            bindingWidgetId == null -> "Default (first: ${selected.title})"
            else -> "Pinned: ${selected.title}"
        }
        return "$glanceKey → $selection"
    }

    /** Envelope field-name gate (no decrypt, no values, no oracle). */
    private fun looksLikeSecretEnvelope(payloadJson: String): Boolean {
        val obj = WidgetJson.fromJson(payloadJson) ?: return false
        return obj.containsKey("alg") && obj.containsKey("kid") &&
            obj.containsKey("nonce") && obj.containsKey("ciphertext")
    }

    /** Last-poll status line, or a never-polled marker before the first poll. */
    fun statusText(status: PollStatus?): String =
        status?.displayText() ?: "never-polled"

    /** Empty-cache line: names the empty state AND the last poll outcome. */
    fun emptyStateText(status: PollStatus?): String =
        "No cached widgets — ${statusText(status)}."
}
