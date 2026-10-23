package com.phonecompanion.app.data

import kotlinx.coroutines.CancellationException
/**
 * Central parser for cached widget payload envelopes (per
 * docs/api-contract.md v1 shapes).
 *
 * Every payload is length-bounded before it reaches the UI
 * ([TEXT_BODY_MAX], [LIST_MAX_ROWS]/[LIST_LABEL_MAX],
 * [STATUS_DETAIL_MAX]) so an oversized envelope cannot blow up a widget,
 * and only parsed fields are ever rendered — the raw envelope JSON must
 * never appear on screen. Malformed JSON yields null (callers show their
 * fallback) rather than throwing.
 *
 * JSON wiring lives in [WidgetJson] (shared with WidgetPoller): this file
 * only applies the length bounds.
 */
object WidgetPayloadParser {
    const val TEXT_BODY_MAX = 2000
    const val TITLE_MAX = 200
    const val LIST_MAX_ROWS = 8
    const val LIST_LABEL_MAX = 200
    const val STATUS_DETAIL_MAX = 160
    const val STATUS_STATE_MAX = 32

    private val KNOWN_STATES = setOf("ok", "warn", "alert", "unknown")

    data class ListItem(val label: String, val done: Boolean)

    data class Status(val state: String, val detail: String)

    /** Raw payload object, or null when [payloadJson] is blank/malformed. */
    fun parseObject(payloadJson: String): Map<String, Any?>? {
        return try {
            WidgetJson.fromJson(payloadJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** `text`: `{ "body": "..." }`, bounded to [TEXT_BODY_MAX]. Null when absent/unparseable. */
    fun parseTextBody(payloadJson: String): String? =
        (parseObject(payloadJson)?.get("body") as? String)?.take(TEXT_BODY_MAX)

    /**
     * `list`: `{ "items": [{ "id", "text", "done" }] }`, capped at [maxRows]
     * with bounded labels. Malformed rows are skipped. Null only when the
     * envelope itself is malformed; a valid envelope without items yields an
     * empty list (callers show their empty hint).
     */
    fun parseListItems(payloadJson: String, maxRows: Int = LIST_MAX_ROWS): List<ListItem>? {
        val obj = parseObject(payloadJson) ?: return null
        val items = obj["items"] as? List<*> ?: return emptyList()
        return items.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { row ->
                val label = row["text"] as? String ?: return@mapNotNull null
                ListItem(label.take(LIST_LABEL_MAX), row["done"] as? Boolean ?: false)
            }
            .take(maxRows.coerceAtLeast(0))
            .toList()
    }

    /**
     * `status`: `{ "state", "detail" }`. Unknown states normalize to
     * `"unknown"`. Null when the envelope is malformed or `detail` is
     * missing (callers show their fallback).
     */
    fun parseStatus(payloadJson: String): Status? {
        val obj = parseObject(payloadJson) ?: return null
        val rawState = (obj["state"] as? String)?.trim()?.take(STATUS_STATE_MAX)?.lowercase()
        val state = if (rawState != null && rawState in KNOWN_STATES) rawState else "unknown"
        val detail = (obj["detail"] as? String)?.take(STATUS_DETAIL_MAX) ?: return null
        return Status(state, detail)
    }

    /** Display titles are DB-controlled but still bounded before render. */
    fun sanitizeTitle(title: String): String = title.take(TITLE_MAX)
}
