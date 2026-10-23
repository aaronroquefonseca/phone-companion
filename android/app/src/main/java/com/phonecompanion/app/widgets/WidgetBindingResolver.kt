package com.phonecompanion.app.widgets

import androidx.glance.GlanceId
import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.data.WidgetCacheEntity
import kotlinx.coroutines.CancellationException

/**
 * Per-instance row selection for the four Glance widgets.
 *
 * Each home-screen instance carries its own binding (`widget_binding`
 * row), so adding/removing one widget never reshuffles the others. All
 * row math here is pure and JVM-testable; only [glanceKey] touches
 * Glance, and only the widgets call it.
 */
object WidgetBindingResolver {

    /**
     * Resolves one instance to its cache row.
     *
     * @param rows cache rows of the instance's type, in DAO id order.
     * @param widgetId the binding's pinned widget id, or null for unassociated.
     * @return null for every unassociated state: null binding (freshly
     * placed, cleared, or storage failure), stale pin (pinned id gone from
     * the cache), or empty cache. The widget renders its explicit
     * "Not associated" state and routes its tap to the configuration —
     * never silently another row's content. See [staleHint] for the
     * re-point signal.
     */
    fun resolve(rows: List<WidgetCacheEntity>, widgetId: String?): WidgetCacheEntity? {
        if (rows.isEmpty()) return null
        if (widgetId == null) return null
        return rows.find { it.id == widgetId }
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
 * Per-instance key for a raw home-screen widget id, without a [GlanceId]
 * in hand (the system configuration flow only hands out the int
 * `appWidgetId`). Must match [glanceKey]: Glance 1.1.1's `AppWidgetId` is
 * a data class over `appWidgetId`, whose `toString()` is exactly
 * `AppWidgetId(appWidgetId=<id>)` (verified against the shipped AAR).
 * Coupled to the pinned Glance version — prefer [glanceKey] from a real
 * `GlanceId` (`GlanceAppWidgetManager.getGlanceIdBy`) wherever one is
 * available and use this only as the pre-first-bind fallback.
 */
fun glanceKeyForAppWidgetId(appWidgetId: Int): String = "AppWidgetId(appWidgetId=$appWidgetId)"

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
