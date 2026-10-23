package com.phonecompanion.app.widgets

import androidx.glance.GlanceId
import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.data.WidgetCacheEntity
import kotlinx.coroutines.CancellationException

/**
 * Per-instance row selection for the four Glance widgets.
 *
 * Replaces the old positional mapping (instance N among the bound ids
 * showed row N): each home-screen instance carries its own binding
 * (`widget_binding` row), so adding/removing one widget never reshuffles
 * the others. All row math here is pure and JVM-testable; only [glanceKey]
 * touches Glance, and only the widgets call it.
 */
object WidgetBindingResolver {

    /**
     * Resolves one instance to its cache row.
     *
     * @param rows cache rows of the instance's type, in DAO id order.
     * @param widgetId the binding's pinned widget id, or null for default.
     * @return null only when [rows] is empty (genuinely missing); a pinned
     * id whose row is gone (stale miss) falls back to the first row, never
     * to a fallback — see [staleHint] for the re-point signal.
     */
    fun resolve(rows: List<WidgetCacheEntity>, widgetId: String?): WidgetCacheEntity? {
        if (rows.isEmpty()) return null
        if (widgetId != null) {
            rows.find { it.id == widgetId }?.let { return it }
        }
        // Default-first-row: unpinned and stale-miss instances show the
        // first row, so single-widget users see zero change from before.
        return rows.first()
    }

    /**
     * Re-point hint for the assignment UI: `was-<old id>` when the binding
     * pins a widget id no longer in the cache, null otherwise (including
     * the unpinned default and the genuinely-empty cache).
     */
    fun staleHint(rows: List<WidgetCacheEntity>, widgetId: String?): String? {
        if (widgetId == null) return null
        if (rows.any { it.id == widgetId }) return null
        return "was-$widgetId"
    }

    /**
     * Bindings to prune: stored rows whose Glance instance is no longer
     * bound on the home screen. Pruning runs inside the
     * `updateAllWidgets` enumeration, where the bound set is already in
     * hand. Never throws.
     */
    fun staleBindings(
        boundKeys: Set<String>,
        stored: List<WidgetBindingEntity>,
    ): List<WidgetBindingEntity> = stored.filter { it.glanceKey !in boundKeys }
}

/**
 * Stable per-instance key for one Glance id. `GlanceId.toString()` wraps
 * the persistent appWidgetId, so the key survives process death and
 * reboot; previews use static layouts and never reach this path.
 */
fun glanceKey(id: GlanceId): String = id.toString()

/**
 * Best-effort binding lookup: miss, empty table, and storage failure all
 * yield null (default first row). Cancellation is always rethrown. Kept
 * tiny so each widget's load path reads identically.
 */
suspend fun loadBindingWidgetId(
    get: suspend () -> String?,
): String? = try {
    get()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
