package com.phonecompanion.app.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.phonecompanion.app.data.WidgetCacheEntity
import kotlinx.coroutines.CancellationException

/**
 * Maps one Glance widget instance to its cache row.
 *
 * Rows of a type are ordered by id (DAO `ORDER BY id`); the instance's
 * position among the bound Glance ids of its class selects its row, so two
 * text widgets can show two different cached texts. An unbound instance
 * (preview, or manager lookup failure) falls back to the first row — and
 * only then.
 */
object WidgetInstancePicker {

    /**
     * Position of [id] among the bound Glance ids of [widgetClass], or a
     * negative value when unbound. Never throws except on cancellation.
     */
    suspend fun boundIndex(
        context: Context,
        widgetClass: Class<out GlanceAppWidget>,
        id: GlanceId,
    ): Int {
        return try {
            GlanceAppWidgetManager(context).getGlanceIds(widgetClass).indexOf(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Pure row selection, unit-testable without Android.
     *
     * @param rows cache rows of one widget type, in DAO order.
     * @param index instance position from [boundIndex], or negative when unbound.
     * @return null when [rows] is empty; the first row when unbound;
     * otherwise the indexed row, wrapping around when there are more
     * instances than rows.
     */
    fun pick(rows: List<WidgetCacheEntity>, index: Int): WidgetCacheEntity? {
        if (rows.isEmpty()) return null
        if (index < 0) return rows.first()
        return rows[index % rows.size]
    }
}
