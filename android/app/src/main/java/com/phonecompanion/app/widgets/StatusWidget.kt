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
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.loadOrFallback
import com.phonecompanion.app.ui.WidgetConfigActivity
import com.phonecompanion.app.ui.WidgetTapRouterActivity
import kotlinx.coroutines.CancellationException

/**
 * Status widget: state badge + detail line.
 * Distinct rendering: single status row ("STATE — detail").
 * Content is per instance (see [WidgetBindingResolver]); state/detail come
 * from the parsed payload, bounded by [WidgetPayloadParser].
 *
 * Every state renders SOMETHING valid: [WidgetContent.statusModel] maps
 * blank/unparseable/missing content to fallback copy (never null, never
 * blank), and a second-chance [provideContent] with static copy guards the
 * composition itself, so a render failure can never propagate to the
 * launcher as the broken-widget UI. An unbound instance renders
 * "Not associated" and its tap opens the configuration.
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = loadStatus(context, id)
        try {
            provideContent {
                Column(
                    modifier = GlanceModifier.padding(12.dp).clickable(tapAction(context, id, model)),
                ) {
                    Row {
                        Text("[" + WidgetFallbackCopy.ellipsize(model.state, STATE_MAX_CHARS) + "] ")
                        Text(WidgetFallbackCopy.ellipsize(model.detail, DETAIL_MAX_CHARS))
                    }
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
                    Row {
                        Text("[unknown] ")
                        Text(WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED))
                    }
                }
            }
        }
    }

    private fun tapAction(
        context: Context,
        id: GlanceId,
        model: WidgetContent.StatusModel,
    ): androidx.glance.action.Action = when (WidgetContent.tapFor(model.paired, model.unbound)) {
        WidgetContent.TapDestination.CONFIG -> actionStartActivityWithIntent(
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(WidgetConfigActivity.EXTRA_PROVIDER_CLASS, PROVIDER)
                .putExtra(WidgetConfigActivity.EXTRA_GLANCE_KEY, glanceKey(id)),
        )
        WidgetContent.TapDestination.ROUTER -> actionStartActivity<WidgetTapRouterActivity>()
    }

    private suspend fun loadStatus(
        context: Context,
        id: GlanceId,
    ): WidgetContent.StatusModel = loadOrFallback(
        WidgetContent.StatusModel(
            state = WidgetFallbackCopy.titleFor("No status", WidgetFallbackCopy.Reason.LOAD_FAILED),
            detail = WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED),
            unbound = true,
            paired = true,
        ),
    ) {
        val paired = isPairedNow(context)
        val db = DatabaseHolder.get(context)
        val rows = db.widgetCacheDao().getByType("status")
        val widgetId = loadBindingWidgetId {
            db.widgetBindingDao().get(PROVIDER, glanceKey(id))?.widgetId
        }
        val model = WidgetContent.statusModel(rows, widgetId, paired)
        if (model.unbound) {
            Log.d(TAG, "status widget unassociated rows=${rows.size}")
        }
        model
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
