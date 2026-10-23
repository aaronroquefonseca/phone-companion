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
 * Status widget: state badge + detail line.
 * Distinct rendering: single status row ("STATE — detail").
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (state, detail) = loadStatus(context)
        provideContent {
            Column {
                Row {
                    Text("[$state] ")
                    Text(detail)
                }
            }
        }
    }

    private suspend fun loadStatus(context: Context): Pair<String, String> {
        return try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
            try {
                val row = db.widgetCacheDao().getByType("status").firstOrNull()
                if (row != null) {
                    "unknown" to row.payloadJson.take(PREVIEW_LEN)
                } else {
                    FALLBACK_STATE to FALLBACK_DETAIL
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            FALLBACK_STATE to FALLBACK_DETAIL
        }
    }

    companion object {
        private const val DB_NAME = "phone-companion.db"
        private const val FALLBACK_STATE = "unknown"
        private const val FALLBACK_DETAIL = "Status — pull to refresh."
        private const val PREVIEW_LEN = 160
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}
