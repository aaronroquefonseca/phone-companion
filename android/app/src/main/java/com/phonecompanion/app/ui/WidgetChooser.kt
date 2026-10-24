package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.widgets.WidgetContent

/**
 * Shared row chooser for one home-screen widget instance.
 *
 * Single owner of the "which cached row does this instance show" mapping,
 * used by both the Widgets-screen instance buttons and the system
 * configuration activity ([WidgetConfigActivity]) — no duplicated
 * option/checked/resolution logic. Options are human words only: the
 * "Not associated" entry plus one line per cached row (title, logical
 * widget id, human updated-at). Raw Glance instance ids never appear.
 */
object WidgetChooser {

    /** The explicit unassociated choice: binding stores null. */
    const val NOT_ASSOCIATED_LABEL = "Not associated"

    data class Option(
        /** Null for [NOT_ASSOCIATED_LABEL], else the cached row id. */
        val widgetId: String?,
        val line: String,
    )

    /**
     * Chooser options: "Not associated" first, then one line per cached
     * row of the type in DAO order. Never throws.
     */
    fun options(
        rows: List<WidgetCacheEntity>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Option> = listOf(Option(null, NOT_ASSOCIATED_LABEL)) + rows.map { row ->
        val title = WidgetContent.nonBlank(row.title, "Untitled")
        Option(row.id, "$title — ${row.id} · updated ${WidgetListUi.humanUpdatedAt(row.updatedAt, nowMs)}")
    }

    /**
     * Pre-checked position for [options]: the pinned row, or "Not
     * associated" when unpinned or stale (the stale pin's `was-<id>` hint
     * is shown separately by the host, never as a selectable row).
     */
    fun checkedIndex(rows: List<WidgetCacheEntity>, pinned: String?): Int {
        if (pinned == null) return 0
        val index = rows.indexOfFirst { it.id == pinned }
        return if (index < 0) 0 else index + 1
    }

    /**
     * Maps a chosen option position back to the binding value (null =
     * explicitly unassociated). Out-of-range positions fail closed to null.
     */
    fun chosenId(rows: List<WidgetCacheEntity>, position: Int): String? {
        if (position <= 0) return null
        return rows.getOrNull(position - 1)?.id
    }

    /**
     * Pending (not yet saved) chooser pick. Tri-state: [None] means the
     * user has not touched the chooser yet (the stored pin decides the
     * check); [Chosen] records the user's pick — including an explicit
     * "Not associated" (null widgetId) — so rotation rebuilds the checked
     * option from the pending pick instead of the stored pin, and Save
     * can never pin a stale value. Pure and JVM-testable; the activity
     * persists it through the instance-state bundle.
     */
    sealed interface PendingPick {
        object None : PendingPick
        data class Chosen(val widgetId: String?) : PendingPick

        fun checkedIndex(rows: List<WidgetCacheEntity>, pinned: String?): Int = when (this) {
            None -> WidgetChooser.checkedIndex(rows, pinned)
            is Chosen -> if (widgetId == null) {
                0
            } else {
                val index = rows.indexOfFirst { it.id == widgetId }
                if (index < 0) 0 else index + 1
            }
        }

        companion object {
            /**
             * Restores the pick from the instance-state bundle (see
             * [WidgetConfigActivity]): [hasPending] plus the picked
             * [widgetId] (absent means an explicit "Not associated").
             */
            fun restore(hasPending: Boolean, widgetId: String?): PendingPick =
                if (hasPending) Chosen(widgetId) else None
        }
    }
}
