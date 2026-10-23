package com.phonecompanion.app.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.widgets.ListWidget
import com.phonecompanion.app.widgets.SecretWidget
import com.phonecompanion.app.widgets.StatusWidget
import com.phonecompanion.app.widgets.TextWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    suspend fun refresh(context: Context) {
        // TODO (P6 data/): fetch GET /api/v1/widgets and upsert into
        // widget_cache before fanning out. No hardcoded hosts/users.
        // Touch the cache so the DAO + entity wiring is exercised even
        // before the network fetch lands; keeps standalone builds green.
        // The singleton is shared — never closed here.
        DatabaseHolder.get(context).widgetCacheDao().getAll()
        updateAllWidgets(context)
    }

    /**
     * Push cached state to each per-type widget. No fully-generic renderer.
     *
     * One child per type under its own [SupervisorJob] so a failing type
     * cannot cancel its siblings; one try/catch per instance id so a broken
     * instance cannot fail its type batch. Cancellation is always rethrown.
     */
    suspend fun updateAllWidgets(context: Context) = coroutineScope {
        listOf(
            async(SupervisorJob()) { updateAll(context, TextWidget()) },
            async(SupervisorJob()) { updateAll(context, SecretWidget()) },
            async(SupervisorJob()) { updateAll(context, ListWidget()) },
            async(SupervisorJob()) { updateAll(context, StatusWidget()) },
        ).awaitAll()
    }

    private suspend inline fun <reified T : GlanceAppWidget> updateAll(context: Context, widget: T) {
        val manager = GlanceAppWidgetManager(context)
        val ids = try {
            manager.getGlanceIds(T::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (id in ids) {
            try {
                widget.update(context, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One broken instance must not fail its siblings.
            }
        }
    }
}
