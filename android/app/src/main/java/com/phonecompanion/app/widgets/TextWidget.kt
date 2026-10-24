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
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.data.loadOrFallback
import com.phonecompanion.app.ui.WidgetConfigActivity
import com.phonecompanion.app.ui.WidgetTapRouterActivity
import kotlinx.coroutines.CancellationException

/**
 * Text widget: title + markdown/plain body.
 * Distinct rendering: two-line column (title over body), no masking.
 * Content is per instance (see [WidgetBindingResolver]) and only parsed
 * payload fields are rendered — never the raw envelope JSON.
 *
 * Every state renders SOMETHING valid: [WidgetContent.textModel] maps
 * blank/unparseable/missing content to fallback copy (never null, never
 * blank), and a second-chance [provideContent] with static copy guards
 * the composition itself, so a render failure can never propagate to the
 * launcher as the broken-widget UI. An unbound instance (freshly placed,
 * cleared, or pinned row gone) renders "Not associated" and its tap opens
 * the configuration; bound taps route through [WidgetTapRouterActivity].
 */
class TextWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = loadText(context, id)
        try {
            provideContent {
                Column(
                    modifier = GlanceModifier.padding(12.dp).clickable(tapAction(context, id, model)),
                ) {
                    Text(WidgetFallbackCopy.ellipsize(model.title, TITLE_MAX_CHARS))
                    Text(WidgetFallbackCopy.ellipsize(model.body, BODY_MAX_CHARS))
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
                    Text("Phone Companion")
                    Text(WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED))
                }
            }
        }
    }

    private fun tapAction(
        context: Context,
        id: GlanceId,
        model: WidgetContent.TextModel,
    ): androidx.glance.action.Action = when (WidgetContent.tapFor(model.paired, model.unbound)) {
        WidgetContent.TapDestination.CONFIG -> actionStartActivityWithIntent(
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(WidgetConfigActivity.EXTRA_PROVIDER_CLASS, PROVIDER)
                .putExtra(WidgetConfigActivity.EXTRA_GLANCE_KEY, glanceKey(id)),
        )
        WidgetContent.TapDestination.ROUTER -> actionStartActivity<WidgetTapRouterActivity>()
    }

    private suspend fun loadText(
        context: Context,
        id: GlanceId,
    ): WidgetContent.TextModel = loadOrFallback(
        WidgetContent.TextModel(
            title = WidgetFallbackCopy.titleFor("Phone Companion", WidgetFallbackCopy.Reason.LOAD_FAILED),
            body = WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED),
            unbound = true,
            paired = true,
        ),
    ) {
        val paired = isPairedNow(context)
        val db = DatabaseHolder.get(context)
        val rows = db.widgetCacheDao().getByType("text")
        val widgetId = loadBindingWidgetId {
            db.widgetBindingDao().get(PROVIDER, glanceKey(id))?.widgetId
        }
        val model = WidgetContent.textModel(rows, widgetId, paired)
        if (model.unbound) {
            Log.d(TAG, "text widget unassociated rows=${rows.size}")
        }
        model
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
