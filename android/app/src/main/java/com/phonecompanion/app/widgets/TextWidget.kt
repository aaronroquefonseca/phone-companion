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
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.data.loadOrFallback
import com.phonecompanion.app.ui.WidgetTapRouterActivity

/**
 * Text widget: title + markdown/plain body.
 * Distinct rendering: two-line column (title over body), no masking.
 * Content is per instance (see [WidgetBindingResolver]) and only parsed
 * payload fields are rendered — never the raw envelope JSON.
 *
 * Tapping the widget opens the app ([WidgetTapRouterActivity] routes to the
 * Widgets screen when paired, Pair when unpaired). Fallback copy is the
 * shared [WidgetFallbackCopy] vocabulary, reason-aware where the reason is
 * known locally (never-paired vs load-failed).
 */
class TextWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val reason = WidgetFallbackCopy.reasonFor(isPairedNow(context))
        val (title, body) = loadText(context, id, reason)
        provideContent {
            Column(
                modifier = GlanceModifier.padding(12.dp).clickable(
                    actionStartActivity<WidgetTapRouterActivity>(),
                ),
            ) {
                Text(WidgetFallbackCopy.ellipsize(title, TITLE_MAX_CHARS))
                Text(WidgetFallbackCopy.ellipsize(body, BODY_MAX_CHARS))
            }
        }
    }

    private suspend fun loadText(
        context: Context,
        id: GlanceId,
        reason: WidgetFallbackCopy.Reason,
    ): Pair<String, String> = loadOrFallback(fallbackFor(reason)) {
        val db = DatabaseHolder.get(context)
        val rows = db.widgetCacheDao().getByType("text")
        val widgetId = loadBindingWidgetId {
            db.widgetBindingDao().get(PROVIDER, glanceKey(id))?.widgetId
        }
        val row = WidgetBindingResolver.resolve(rows, widgetId)
            ?: return@loadOrFallback fallbackFor(reason).also {
                Log.d(TAG, "text widget fallback rows=0")
            }
        val body = WidgetPayloadParser.parseTextBody(row.payloadJson)
            ?: return@loadOrFallback fallbackFor(reason).also {
                // Shape only (counts/bytes, never titles/bodies): names the
                // poison-row state that used to present as a render bug.
                Log.d(TAG, "text widget fallback rows=${rows.size} payloadBytes=${row.payloadJson.toByteArray(Charsets.UTF_8).size}")
            }
        WidgetPayloadParser.sanitizeTitle(row.title) to body
    }

    companion object {
        private const val TAG = "TextWidget"
        private const val TITLE_MAX_CHARS = 80
        private const val BODY_MAX_CHARS = 240

        /** Stable provider key for the `widget_binding` table. */
        val PROVIDER: String = TextWidget::class.java.name

        fun fallbackFor(reason: WidgetFallbackCopy.Reason): Pair<String, String> =
            WidgetFallbackCopy.titleFor("Phone Companion", reason) to
                WidgetFallbackCopy.bodyFor(reason)
    }
}

/** Local pairing check for reason-aware fallbacks; read failure fails closed to paired. */
internal fun isPairedNow(context: Context): Boolean =
    try {
        SecureStorage.getInstance(context).isPaired
    } catch (_: Exception) {
        true
    }

class TextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TextWidget()
}
