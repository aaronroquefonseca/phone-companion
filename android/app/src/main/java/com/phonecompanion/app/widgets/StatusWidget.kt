package com.phonecompanion.app.widgets

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.data.loadOrFallback
import com.phonecompanion.app.ui.WidgetTapRouterActivity

/**
 * Status widget: state badge + detail line.
 * Distinct rendering: single status row ("STATE — detail").
 * Content is per instance (see [WidgetBindingResolver]); state/detail come
 * from the parsed payload, bounded by [WidgetPayloadParser].
 *
 * Tapping opens the app (see [WidgetTapRouterActivity]); empty states use
 * the shared [WidgetFallbackCopy] vocabulary.
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val reason = WidgetFallbackCopy.reasonFor(isPairedNow(context))
        val (state, detail) = loadStatus(context, id, reason)
        provideContent {
            Column(
                modifier = GlanceModifier.padding(12.dp).clickable(
                    actionStartActivity<WidgetTapRouterActivity>(),
                ),
            ) {
                Row {
                    Text("[" + WidgetFallbackCopy.ellipsize(state, STATE_MAX_CHARS) + "] ")
                    Text(WidgetFallbackCopy.ellipsize(detail, DETAIL_MAX_CHARS))
                }
            }
        }
    }

    private suspend fun loadStatus(
        context: Context,
        id: GlanceId,
        reason: WidgetFallbackCopy.Reason,
    ): Pair<String, String> = loadOrFallback(fallbackFor(reason)) {
        val db = DatabaseHolder.get(context)
        val rows = db.widgetCacheDao().getByType("status")
        val widgetId = loadBindingWidgetId {
            db.widgetBindingDao().get(PROVIDER, glanceKey(id))?.widgetId
        }
        val row = WidgetBindingResolver.resolve(rows, widgetId)
            ?: return@loadOrFallback fallbackFor(reason).also {
                Log.d(TAG, "status widget fallback rows=0")
            }
        val status = WidgetPayloadParser.parseStatus(row.payloadJson)
            ?: return@loadOrFallback fallbackFor(reason).also {
                Log.d(TAG, "status widget fallback rows=${rows.size} payloadBytes=${row.payloadJson.toByteArray(Charsets.UTF_8).size}")
            }
        status.state to status.detail
    }

    companion object {
        private const val TAG = "StatusWidget"
        private const val STATE_MAX_CHARS = 24
        private const val DETAIL_MAX_CHARS = 120

        /** Stable provider key for the `widget_binding` table. */
        val PROVIDER: String = StatusWidget::class.java.name

        fun fallbackFor(reason: WidgetFallbackCopy.Reason): Pair<String, String> =
            WidgetFallbackCopy.titleFor("No status", reason) to
                WidgetFallbackCopy.bodyFor(reason)
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}
