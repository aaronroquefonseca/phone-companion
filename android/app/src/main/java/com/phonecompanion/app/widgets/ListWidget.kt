package com.phonecompanion.app.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.text.Text
import androidx.room.Room
import com.phonecompanion.app.data.AppDatabase

/**
 * List widget: title + one row per item with a done marker.
 * Distinct rendering: rows of "[x]/[ ] label" — no other type renders rows.
 */
class ListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (title, rows) = loadList(context)
        provideContent {
            Column {
                Text(title)
                for (row in rows.take(MAX_ROWS)) {
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

    private data class RowUi(val label: String, val done: Boolean)

    private suspend fun loadList(context: Context): Pair<String, List<RowUi>> {
        return try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
            try {
                val row = db.widgetCacheDao().getByType("list").firstOrNull()
                    ?: return FALLBACK_TITLE to emptyList()
                // Payload parsing is P6's data-layer shape; until then show
                // the cached envelope marker as a single row.
                row.title to listOf(RowUi(row.payloadJson.take(PREVIEW_LEN), false))
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            FALLBACK_TITLE to emptyList()
        }
    }

    companion object {
        private const val DB_NAME = "phone-companion.db"
        private const val FALLBACK_TITLE = "List"
        private const val EMPTY_HINT = "No items — pull to refresh."
        private const val MAX_ROWS = 8
        private const val PREVIEW_LEN = 120
    }
}

class ListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListWidget()
}
