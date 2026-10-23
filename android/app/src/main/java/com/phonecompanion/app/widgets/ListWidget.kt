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
 * List widget: title + one row per item with a done marker.
 * Distinct rendering: rows of "[x]/[ ] label" — no other type renders rows.
 * Content is per instance (see [WidgetInstancePicker]); rows come from the
 * parsed `items` payload, capped and bounded by [WidgetPayloadParser].
 */
class ListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (title, rows) = loadList(context, id)
        provideContent {
            Column {
                Text(title)
                for (row in rows) {
                    Row {
                        Text(if (row.done) "[x] " else "[ ] ")
                        Text(row.label)
                    }
                }
                if (rows.isEmpty()) {
                    Text(EMPTY_HINT)
                }
            }
        }
    }

    private suspend fun loadList(
        context: Context,
        id: GlanceId,
    ): Pair<String, List<WidgetPayloadParser.ListItem>> =
        loadOrFallback(FALLBACK_TITLE to emptyList<WidgetPayloadParser.ListItem>()) {
            val rows = DatabaseHolder.get(context).widgetCacheDao().getByType("list")
            val row = WidgetInstancePicker.pick(
                rows,
                WidgetInstancePicker.boundIndex(context, ListWidget::class.java, id),
            ) ?: return@loadOrFallback FALLBACK_TITLE to emptyList()
            val items = WidgetPayloadParser.parseListItems(row.payloadJson) ?: emptyList()
            WidgetPayloadParser.sanitizeTitle(row.title) to items
        }

    companion object {
        private const val FALLBACK_TITLE = "List"
        private const val EMPTY_HINT = "No items yet."
    }
}

class ListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListWidget()
}
