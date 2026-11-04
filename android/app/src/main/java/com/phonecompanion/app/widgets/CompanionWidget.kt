package com.phonecompanion.app.widgets

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.ui.WidgetConfigActivity
import com.phonecompanion.app.ui.WidgetTapRouterActivity
import kotlinx.coroutines.flow.first

/**
 * Single home-screen widget: one picker entry ("Widget") that can show any
 * cached logical widget.
 *
 * The picker shows label + description only (no static preview). Placing the
 * widget opens the configuration ([WidgetConfigActivity]): pick any cached
 * row (text/secret/list/status) or leave it unassociated. A freshly placed
 * widget with no binding renders the explicit not-associated state and its
 * tap opens the configuration — never another row's content, never null.
 *
 * Per-type rendering is preserved internally: [resolveForRow] dispatches on
 * the bound row's `type` to the existing [WidgetContent] builders (the same
 * models and the same composition the four per-type widgets used — no
 * fixed dp beyond the shared 12dp padding / 4dp spacer that already adapts
 * to the smallest sizes). Unknown types fall back to the unassociated state
 * (tap-to-configure) rather than another row's content.
 *
 * The composition observes Room and credential changes for its entire
 * session. update() alone does not re-run provideGlance while it is active.
 */
class CompanionWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val models = widgetRenderModels(context, id)
        val initial = models.first()
        provideContent {
            val model by models.collectAsState(initial)
            CompanionComposition(model, tapAction(context, id, model))
        }
    }

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        // Composition runs asynchronously; a try/catch around provideContent
        // cannot handle it. Log only the class, never payload-bearing messages.
        Log.e("CompanionWidget", "widget composition failed error=${throwable.javaClass.simpleName}")
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }

    private fun tapAction(
        context: Context,
        id: GlanceId,
        model: CompanionContent.Render,
    ): androidx.glance.action.Action {
        val (paired, unbound) = CompanionContent.tapState(model)
        return when (WidgetContent.tapFor(paired, unbound)) {
            WidgetContent.TapDestination.CONFIG -> actionStartActivityWithIntent(
                Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(WidgetConfigActivity.EXTRA_PROVIDER_CLASS, PROVIDER)
                    .putExtra(WidgetConfigActivity.EXTRA_GLANCE_KEY, glanceKey(id)),
            )
            WidgetContent.TapDestination.ROUTER -> actionStartActivity<WidgetTapRouterActivity>()
        }
    }

    companion object {
        /** Stable provider key for the `widget_binding` table (the only one). */
        val PROVIDER: String = CompanionWidget::class.java.name

        /** Single masked line; mentions masking, never distinguishes failure modes. */
        const val MASKED = WidgetFallbackCopy.SECRET_MASKED
    }
}

/**
 * Pure dispatch for the single widget: which render does one bound row (or
 * no row) produce. JVM-testable without Glance or Android storage.
 *
 * - `row == null` (no binding, stale pin, empty cache, storage failure) →
 *   explicit not-associated (tap-to-configure when paired).
 * - known `type` → the matching [WidgetContent] model (which itself maps
 *   blank/malformed payloads to non-blank fallback copy — never null,
 *   never blank, never another row's content).
 * - unknown `type` → not-associated (tap-to-configure), never a throw.
 * - `secret` maps EVERY decrypt outcome (null visible, empty visible) to
 *   the single masked line via [WidgetContent.secretModel] (no oracle).
 */
object CompanionContent {

    sealed interface Render {
        data class Unbound(val title: String, val body: String, val paired: Boolean) : Render
        data class Text(val model: WidgetContent.TextModel) : Render
        data class List(val model: WidgetContent.ListModel) : Render
        data class Status(val model: WidgetContent.StatusModel) : Render
        data class Secret(val model: WidgetContent.SecretModel) : Render
    }

    /** Load-failure fallback: valid, paired, unbound (never null). */
    fun unboundFallback(): Render.Unbound = unboundFor(paired = true)

    fun unboundFor(paired: Boolean): Render.Unbound {
        val reason = WidgetFallbackCopy.reasonFor(paired)
        return Render.Unbound(
            title = WidgetContent.unboundTitle("Widget", reason),
            body = WidgetContent.unboundBody(reason),
            paired = paired,
        )
    }

    fun resolveForRow(
        row: WidgetCacheEntity?,
        paired: Boolean,
        visibleSecret: String?,
    ): Render {
        if (row == null) return unboundFor(paired)
        return when (row.type) {
            "text" -> Render.Text(WidgetContent.textModel(listOf(row), row.id, paired))
            "list" -> Render.List(WidgetContent.listModel(listOf(row), row.id, paired))
            "status" -> Render.Status(WidgetContent.statusModel(listOf(row), row.id, paired))
            "secret" -> Render.Secret(
                WidgetContent.secretModel(row, neverPaired = !paired, visible = visibleSecret),
            )
            else -> unboundFor(paired)
        }
    }

    /** (paired, unbound) pair for [WidgetContent.tapFor]. */
    fun tapState(render: Render): Pair<Boolean, Boolean> = when (render) {
        is Render.Unbound -> render.paired to true
        is Render.Text -> render.model.paired to render.model.unbound
        is Render.List -> render.model.paired to render.model.unbound
        is Render.Status -> render.model.paired to render.model.unbound
        is Render.Secret -> render.model.paired to render.model.unbound
    }

    /** Every rendered string, for non-blank regression assertions. */
    fun texts(render: Render): List<String> = when (render) {
        is Render.Unbound -> listOf(render.title, render.body)
        is Render.Text -> listOf(render.model.title, render.model.body)
        is Render.List -> listOf(render.model.title, render.model.emptyHint) +
            render.model.visible.map { it.label } +
            listOfNotNull(render.model.overflow)
        is Render.Status -> listOf(render.model.state, render.model.detail)
        is Render.Secret -> listOf(render.model.title, render.model.body)
    }
}

class CompanionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompanionWidget()
}

/**
 * Shared composition for every render state. Per-type branches reuse the
 * exact layouts the four per-type widgets used (12dp padding, 4dp list
 * spacer, 2dp row padding, "[x]/[ ]" list markers, "[state] detail" status
 * row) so small-size behavior is unchanged: no fixed dp, everything wraps
 * the smallest sizes.
 */
@androidx.compose.runtime.Composable
internal fun CompanionComposition(
    model: CompanionContent.Render,
    tap: androidx.glance.action.Action,
) {
    when (model) {
        is CompanionContent.Render.Unbound -> Column(
            modifier = GlanceModifier.padding(12.dp).clickable(tap),
        ) {
            Text(WidgetFallbackCopy.ellipsize(model.title, 80))
            Text(WidgetFallbackCopy.ellipsize(model.body, 240))
        }
        is CompanionContent.Render.Text -> Column(
            modifier = GlanceModifier.padding(12.dp).clickable(tap),
        ) {
            Text(WidgetFallbackCopy.ellipsize(model.model.title, 80))
            Text(WidgetFallbackCopy.ellipsize(model.model.body, 240))
        }
        is CompanionContent.Render.List -> Column(
            modifier = GlanceModifier.padding(12.dp).clickable(tap),
        ) {
            Text(WidgetFallbackCopy.ellipsize(model.model.title, 80))
            Spacer(modifier = GlanceModifier.height(4.dp))
            for (row in model.model.visible) {
                Row(modifier = GlanceModifier.padding(vertical = 2.dp)) {
                    Text(if (row.done) "[x] " else "[ ] ")
                    Text(WidgetFallbackCopy.ellipsize(row.label, 60))
                }
            }
            if (model.model.visible.isEmpty()) {
                Text(WidgetFallbackCopy.ellipsize(model.model.emptyHint, 240))
            }
            model.model.overflow?.let { Text(it) }
        }
        is CompanionContent.Render.Status -> Column(
            modifier = GlanceModifier.padding(12.dp).clickable(tap),
        ) {
            Row {
                Text("[" + WidgetFallbackCopy.ellipsize(model.model.state, 24) + "] ")
                Text(WidgetFallbackCopy.ellipsize(model.model.detail, 120))
            }
        }
        is CompanionContent.Render.Secret -> Column(
            modifier = GlanceModifier.padding(12.dp).clickable(tap),
        ) {
            Text(WidgetFallbackCopy.ellipsize(model.model.title, 80))
            Text(WidgetFallbackCopy.ellipsize(model.model.body, 240))
        }
    }
}

/**
 * Fail-closed secret resolution for one selected row. Returns null for
 * EVERY failure mode — missing credentials (post-[com.phonecompanion.app.data.SecureStorage.clearCredentials]
 * unpaired state), empty cache, `secret_ref`/kid mismatch, and auth-tag
 * failure — which the widget renders as the single [CompanionWidget.MASKED]
 * "needs refresh" state (no oracle).
 */
internal fun resolveVisibleSecret(
    deviceId: String?,
    deviceKey: String?,
    row: WidgetCacheEntity?,
): String? {
    if (deviceId.isNullOrBlank() || deviceKey.isNullOrBlank()) return null
    if (row == null) return null
    // Triple equality (client leg): cached secret_ref must agree
    // with the stored device id and the envelope kid (checked
    // inside SecretCrypto). Any mismatch fails closed to null.
    if (row.secretRef != null && row.secretRef != deviceId) return null
    return SecretCrypto.decryptSecretEnvelope(
        envelopeJson = row.payloadJson,
        deviceKeyBase64 = deviceKey,
        expectedKid = deviceId,
        widgetId = row.id,
    )
}

/**
 * Decrypt hook (P10): real AES-256-GCM decrypt of the secret envelope,
 * kept as a top-level function for tests and legacy callers.
 *
 * Prefer [SecretCrypto.decryptSecretEnvelope] (takes the device key
 * explicitly). This wrapper keeps the original P7 signature and fails
 * closed to null on every failure — including the missing-key case, which
 * the widget maps to the single needs-refresh UI state.
 *
 * @param ciphertextEnvelopeJson the widget payload JSON (P5 envelope).
 * @param kid the expected device id (must match envelope kid).
 * @param widgetId the widget id used as the AAD binding.
 * @param deviceKeyBase64 base64 per-device key; null (no key) fails closed.
 */
fun decryptSecretStub(
    ciphertextEnvelopeJson: String?,
    kid: String?,
    widgetId: String,
    deviceKeyBase64: String? = null,
): String? = SecretCrypto.decryptSecretEnvelope(
    envelopeJson = ciphertextEnvelopeJson,
    deviceKeyBase64 = deviceKeyBase64,
    expectedKid = kid,
    widgetId = widgetId,
)
