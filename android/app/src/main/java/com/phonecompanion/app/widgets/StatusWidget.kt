package com.phonecompanion.app.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.data.loadOrFallback

/**
 * Status widget: state badge + detail line.
 * Distinct rendering: single status row ("STATE — detail").
 * Content is per instance (see [WidgetInstancePicker]); state/detail come
 * from the parsed payload, bounded by [WidgetPayloadParser].
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (state, detail) = loadStatus(context, id)
        provideContent {
            Column {
                Row {
                    Text("[$state] ")
                    Text(detail)
                }
            }
        }
    }

    private suspend fun loadStatus(context: Context, id: GlanceId): Pair<String, String> =
        loadOrFallback(FALLBACK_STATE to FALLBACK_DETAIL) {
            val rows = DatabaseHolder.get(context).widgetCacheDao().getByType("status")
            val row = WidgetInstancePicker.pick(
                rows,
                WidgetInstancePicker.boundIndex(context, StatusWidget::class.java, id),
            ) ?: return@loadOrFallback FALLBACK_STATE to FALLBACK_DETAIL
            val status = WidgetPayloadParser.parseStatus(row.payloadJson)
                ?: return@loadOrFallback FALLBACK_STATE to FALLBACK_DETAIL
            status.state to status.detail
        }

    companion object {
        private const val FALLBACK_STATE = "No status"
        private const val FALLBACK_DETAIL = "Nothing to show yet."
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}
