package com.phonecompanion.app.widgets

import android.content.Context
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.data.loadOrFallback
import com.phonecompanion.app.ui.WidgetTapRouterActivity

/**
 * List widget: title + one row per item with a done marker.
 * Distinct rendering: rows of "[x]/[ ] label" — no other type renders rows.
 * Content is per instance (see [WidgetInstancePicker]); rows come from the
 * parsed `items` payload, capped and bounded by [WidgetPayloadParser].
 *
 * Only the first [WidgetFallbackCopy.LIST_MAX_SHOWN] items render; the rest
 * collapse into a "+N more" overflow hint. Labels are ellipsized in code
 * (Glance 1.1 Text has no maxLines). Tapping opens the app (see
 * [WidgetTapRouterActivity]); empty states use the shared
 * [WidgetFallbackCopy] vocabulary.
 */
class ListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val reason = WidgetFallbackCopy.reasonFor(isPairedNow(context))
        val (title, rows) = loadList(context, id, reason)
        val (visible, overflow) = WidgetFallbackCopy.visibleWithOverflow(rows)
        provideContent {
            Column(
                modifier = GlanceModifier.padding(12.dp).clickable(
                    actionStartActivity<WidgetTapRouterActivity>(),
                ),
            ) {
                Text(WidgetFallbackCopy.ellipsize(title, TITLE_MAX_CHARS))
                Spacer(modifier = GlanceModifier.height(4.dp))
                for (row in visible) {
                    Row(modifier = GlanceModifier.padding(vertical = 2.dp)) {
                        Text(if (row.done) "[x] " else "[ ] ")
                        Text(WidgetFallbackCopy.ellipsize(row.label, LABEL_MAX_CHARS))
                    }
                }
                if (visible.isEmpty()) {
                    Text(WidgetFallbackCopy.bodyFor(reason))
                }
                overflow?.let { Text(it) }
            }
        }
    }

    private suspend fun loadList(
        context: Context,
        id: GlanceId,
        reason: WidgetFallbackCopy.Reason,
    ): Pair<String, List<WidgetPayloadParser.ListItem>> =
        loadOrFallback(fallbackFor(reason)) {
            val rows = DatabaseHolder.get(context).widgetCacheDao().getByType("list")
            val row = WidgetInstancePicker.pick(
                rows,
                WidgetInstancePicker.boundIndex(context, ListWidget::class.java, id),
            ) ?: return@loadOrFallback fallbackFor(reason)
            val items = WidgetPayloadParser.parseListItems(row.payloadJson)
                ?: return@loadOrFallback fallbackFor(reason)
            WidgetPayloadParser.sanitizeTitle(row.title) to items
        }

    companion object {
        private const val TITLE_MAX_CHARS = 80
        private const val LABEL_MAX_CHARS = 60

        fun fallbackFor(
            reason: WidgetFallbackCopy.Reason,
        ): Pair<String, List<WidgetPayloadParser.ListItem>> =
            WidgetFallbackCopy.titleFor("List", reason) to emptyList()
    }
}

class ListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListWidget()
}
