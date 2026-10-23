package com.phonecompanion.app.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.data.loadOrFallback

/**
 * Text widget: title + markdown/plain body.
 * Distinct rendering: two-line column (title over body), no masking.
 * Content is per instance (see [WidgetInstancePicker]) and only parsed
 * payload fields are rendered — never the raw envelope JSON.
 */
class TextWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (title, body) = loadText(context, id)
        provideContent {
            Column {
                Text(title)
                Text(body)
            }
        }
    }

    private suspend fun loadText(context: Context, id: GlanceId): Pair<String, String> =
        loadOrFallback(FALLBACK_TITLE to FALLBACK_BODY) {
            val rows = DatabaseHolder.get(context).widgetCacheDao().getByType("text")
            val row = WidgetInstancePicker.pick(
                rows,
                WidgetInstancePicker.boundIndex(context, TextWidget::class.java, id),
            ) ?: return@loadOrFallback FALLBACK_TITLE to FALLBACK_BODY
            val body = WidgetPayloadParser.parseTextBody(row.payloadJson) ?: FALLBACK_BODY
            WidgetPayloadParser.sanitizeTitle(row.title) to body
        }

    companion object {
        private const val FALLBACK_TITLE = "Phone Companion"
        private const val FALLBACK_BODY = "Text widget — pull to refresh."
    }
}

class TextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TextWidget()
}
