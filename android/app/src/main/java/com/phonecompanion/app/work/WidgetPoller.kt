package com.phonecompanion.app.work

import android.content.Context
import androidx.room.Room
import com.phonecompanion.app.data.AppDatabase
import com.phonecompanion.app.widgets.ListWidget
import com.phonecompanion.app.widgets.SecretWidget
import com.phonecompanion.app.widgets.StatusWidget
import com.phonecompanion.app.widgets.TextWidget

/**
 * Shared generic polling plumbing (widgets PR / P7).
 *
 * Single code path used by both the hourly [RefreshWorker] and the manual
 * refresh entrypoint: read the Room widget cache, then push it to every
 * per-type Glance widget. Per-type rendering lives in widgets/ — this file
 * never renders; it only fans out.
 *
 * Network fetch (GET /api/v1/widgets with the device Bearer token, base URL
 * config, ETag handling) is owned by the data-layer PR (P6). Until then
 * [refresh] propagates the last cached state so the worker, scheduling, and
 * Glance update path are verifiable standalone on P0.
 */
object WidgetPoller {

    private const val DB_NAME = "phone-companion.db"

    suspend fun refresh(context: Context) {
        // TODO (P6 data/): fetch GET /api/v1/widgets and upsert into
        // widget_cache before fanning out. No hardcoded hosts/users.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .fallbackToDestructiveMigration()
            .build()
        try {
            // Touch the cache so the DAO + entity wiring is exercised even
            // before the network fetch lands; keeps standalone builds green.
            db.widgetCacheDao().getAll()
        } finally {
            db.close()
        }
        updateAllWidgets(context)
    }

    /** Push cached state to each per-type widget. No fully-generic renderer. */
    suspend fun updateAllWidgets(context: Context) {
        TextWidget().update(context)
        SecretWidget().update(context)
        ListWidget().update(context)
        StatusWidget().update(context)
    }

    private suspend fun TextWidget.update(context: Context) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        for (id in manager.getGlanceIds(TextWidget::class.java)) {
            update(context, id)
        }
    }

    private suspend fun SecretWidget.update(context: Context) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        for (id in manager.getGlanceIds(SecretWidget::class.java)) {
            update(context, id)
        }
    }

    private suspend fun ListWidget.update(context: Context) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        for (id in manager.getGlanceIds(ListWidget::class.java)) {
            update(context, id)
        }
    }

    private suspend fun StatusWidget.update(context: Context) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        for (id in manager.getGlanceIds(StatusWidget::class.java)) {
            update(context, id)
        }
    }
}
