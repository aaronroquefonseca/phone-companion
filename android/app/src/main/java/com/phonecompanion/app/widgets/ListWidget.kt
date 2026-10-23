package com.phonecompanion.app.widgets

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityWithIntent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.data.loadOrFallback
import com.phonecompanion.app.ui.WidgetConfigActivity
import com.phonecompanion.app.ui.WidgetTapRouterActivity
import kotlinx.coroutines.CancellationException

/**
 * List widget: title + one row per item with a done marker.
 * Distinct rendering: rows of "[x]/[ ] label" — no other type renders rows.
 * Content is per instance (see [WidgetBindingResolver]); rows come from the
 * parsed `items` payload, capped and bounded by [WidgetPayloadParser].
 *
 * Only the first [WidgetFallbackCopy.LIST_MAX_SHOWN] items render; the rest
 * collapse into a "+N more" overflow hint. Labels are ellipsized in code
 * (Glance 1.1 Text has no maxLines). Every state renders SOMETHING valid:
 * [WidgetContent.listModel] maps blank/unparseable/missing content to
 * fallback copy (never null, never blank), and a second-chance
 * [provideContent] with static copy guards the composition itself, so a
 * render failure can never propagate to the launcher as the broken-widget
 * UI. An unbound instance renders "Not associated" and its tap opens the
 * configuration.
 */
class ListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = loadList(context, id)
        try {
            provideContent {
                Column(
                    modifier = GlanceModifier.padding(12.dp).clickable(tapAction(context, id, model)),
                ) {
                    Text(WidgetFallbackCopy.ellipsize(model.title, TITLE_MAX_CHARS))
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    for (row in model.visible) {
                        Row(modifier = GlanceModifier.padding(vertical = 2.dp)) {
                            Text(if (row.done) "[x] " else "[ ] ")
                            Text(WidgetFallbackCopy.ellipsize(row.label, LABEL_MAX_CHARS))
                        }
                    }
                    if (model.visible.isEmpty()) {
                        Text(WidgetFallbackCopy.ellipsize(model.emptyHint, BODY_MAX_CHARS))
                    }
                    model.overflow?.let { Text(it) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fail closed to static copy: never throw, never post null.
            provideContent {
                Column(
                    modifier = GlanceModifier.padding(12.dp)
                        .clickable(actionStartActivity<WidgetTapRouterActivity>()),
                ) {
                    Text("List")
                    Text(WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED))
                }
            }
        }
    }

    private fun tapAction(
        context: Context,
        id: GlanceId,
        model: WidgetContent.ListModel,
    ): androidx.glance.action.Action = when (WidgetContent.tapFor(model.paired, model.unbound)) {
        WidgetContent.TapDestination.CONFIG -> actionStartActivityWithIntent(
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(WidgetConfigActivity.EXTRA_PROVIDER_CLASS, PROVIDER)
                .putExtra(WidgetConfigActivity.EXTRA_GLANCE_KEY, glanceKey(id)),
        )
        WidgetContent.TapDestination.ROUTER -> actionStartActivity<WidgetTapRouterActivity>()
    }

    private suspend fun loadList(
        context: Context,
        id: GlanceId,
    ): WidgetContent.ListModel = loadOrFallback(
        WidgetContent.ListModel(
            title = WidgetFallbackCopy.titleFor("List", WidgetFallbackCopy.Reason.LOAD_FAILED),
            visible = emptyList(),
            overflow = null,
            emptyHint = WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED),
            unbound = true,
            paired = true,
        ),
    ) {
        val paired = isPairedNow(context)
        val db = DatabaseHolder.get(context)
        val rows = db.widgetCacheDao().getByType("list")
        val widgetId = loadBindingWidgetId {
            db.widgetBindingDao().get(PROVIDER, glanceKey(id))?.widgetId
        }
        val model = WidgetContent.listModel(rows, widgetId, paired)
        if (model.unbound) {
            Log.d(TAG, "list widget unassociated rows=${rows.size}")
        }
        model
    }

    companion object {
        private const val TAG = "ListWidget"
        private const val TITLE_MAX_CHARS = 80
        private const val LABEL_MAX_CHARS = 60
        private const val BODY_MAX_CHARS = 240

        /** Stable provider key for the `widget_binding` table. */
        val PROVIDER: String = ListWidget::class.java.name

        fun fallbackFor(
            reason: WidgetFallbackCopy.Reason,
        ): Pair<String, List<WidgetPayloadParser.ListItem>> =
            WidgetFallbackCopy.titleFor("List", reason) to emptyList()
    }
}

class ListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListWidget()
}
