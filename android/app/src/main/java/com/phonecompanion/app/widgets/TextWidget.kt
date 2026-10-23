package com.phonecompanion.app.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.text.Text
import androidx.room.Room
import com.phonecompanion.app.data.AppDatabase

/**
 * Text widget: title + markdown/plain body.
 * Distinct rendering: two-line column (title over body), no masking.
 */
class TextWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (title, body) = loadFirstText(context)
        provideContent {
            Column {
                Text(title)
                Text(body)
            }
        }
    }

    private suspend fun loadFirstText(context: Context): Pair<String, String> {
        return try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
            try {
                val row = db.widgetCacheDao().getByType("text").firstOrNull()
                if (row != null) row.title to row.payloadJson else FALLBACK_TITLE to FALLBACK_BODY
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            FALLBACK_TITLE to FALLBACK_BODY
        }
    }

    companion object {
        private const val DB_NAME = "phone-companion.db"
        private const val FALLBACK_TITLE = "Phone Companion"
        private const val FALLBACK_BODY = "Text widget — pull to refresh."
    }
}

class TextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TextWidget()
}
