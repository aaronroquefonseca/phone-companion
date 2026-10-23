package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.work.PollStatus

/**
 * Pure text mapping for the widget list screen.
 *
 * Framework-free so the row/status rendering is JVM-testable; [WidgetsActivity]
 * only binds these strings to TextViews. Every cached row shows
 * id/type/title/updated_at, and the poll status line always shows the last
 * poll outcome — never a silent empty state.
 */
object WidgetListUi {

    /** One cached row: id, type, title, and updated_at. */
    fun rowText(id: String, type: String, title: String, updatedAt: String): String =
        "$id • $type • $title • $updatedAt"

    /** Row texts for every cached widget, in the DAO's id order. */
    fun rowTexts(entities: List<WidgetCacheEntity>): List<String> =
        entities.map { rowText(it.id, it.type, it.title, it.updatedAt) }

    /** Last-poll status line, or a never-polled marker before the first poll. */
    fun statusText(status: PollStatus?): String =
        status?.displayText() ?: "never-polled"

    /** Empty-cache line: names the empty state AND the last poll outcome. */
    fun emptyStateText(status: PollStatus?): String =
        "No cached widgets — ${statusText(status)}."
}
