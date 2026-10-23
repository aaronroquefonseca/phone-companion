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
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.ui.WidgetTapRouterActivity
import kotlinx.coroutines.CancellationException

/**
 * Secret widget: fail-closed masked rendering (P10 SECRET ROUND-TRIP).
 *
 * Distinct rendering: plaintext column on decrypt success, otherwise the
 * single masked placeholder — never plaintext on auth failure, never a
 * crash. Decryption is AES-256-GCM via [SecretCrypto] using the stored
 * per-device key selected by kid; ALL failures map to the one masked
 * "needs refresh" state (no oracle: the widget never distinguishes
 * kid-mismatch from auth-tag failure on screen or in logs). Rotation
 * (re-pair) makes cached envelopes undecryptable until the next poll
 * fetches freshly-encrypted ones. The masked copy says the value is hidden
 * (see [WidgetFallbackCopy.SECRET_MASKED]).
 *
 * Never-paired (no credentials at all) is not a decrypt failure and says so
 * plainly with pairing guidance; every other empty state renders the single
 * masked line. Per-instance content: same [WidgetInstancePicker] row
 * selection as the other widgets, with the title bounded by
 * [WidgetPayloadParser.sanitizeTitle]; resolution stays fail-closed
 * ([resolveVisibleSecret] null → [SecretWidget.MASKED]). Database access
 * is one shared open via [DatabaseHolder] — never a per-call builder,
 * never closed here. Tapping opens the app (see [WidgetTapRouterActivity]).
 */
class SecretWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        var title = WidgetFallbackCopy.titleFor(FALLBACK_LABEL, WidgetFallbackCopy.Reason.LOAD_FAILED)
        // Fail closed: any decrypt failure shows the masked state.
        var visible: String?
        var neverPaired: Boolean
        try {
            val storage = SecureStorage.getInstance(context)
            neverPaired = !storage.isPaired
            val db = DatabaseHolder.get(context)
            val rows = db.widgetCacheDao().getByType("secret")
            val row = WidgetInstancePicker.pick(
                rows,
                WidgetInstancePicker.boundIndex(context, SecretWidget::class.java, id),
            )
            title = row?.let { WidgetPayloadParser.sanitizeTitle(it.title) } ?: title
            visible = if (neverPaired) null else resolveVisibleSecret(
                storage.deviceId,
                storage.deviceKey,
                row,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Single masked outcome: never leak failure details.
            title = WidgetFallbackCopy.titleFor(FALLBACK_LABEL, WidgetFallbackCopy.Reason.LOAD_FAILED)
            visible = null
            neverPaired = false
        }
        val body = when {
            neverPaired -> WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.NEVER_PAIRED)
            else -> visible ?: MASKED
        }
        val shownTitle = when {
            neverPaired -> WidgetFallbackCopy.titleFor(FALLBACK_LABEL, WidgetFallbackCopy.Reason.NEVER_PAIRED)
            else -> title
        }
        provideContent {
            Column(
                modifier = GlanceModifier.padding(12.dp).clickable(
                    actionStartActivity<WidgetTapRouterActivity>(),
                ),
            ) {
                Text(WidgetFallbackCopy.ellipsize(shownTitle, TITLE_MAX_CHARS))
                Text(WidgetFallbackCopy.ellipsize(body, BODY_MAX_CHARS))
            }
        }
    }

    companion object {
        private const val FALLBACK_LABEL = "Secret"
        private const val TITLE_MAX_CHARS = 80
        private const val BODY_MAX_CHARS = 240

        /** Single masked line; mentions masking, never distinguishes failure modes. */
        const val MASKED = WidgetFallbackCopy.SECRET_MASKED
    }
}

/**
 * Fail-closed secret resolution for one selected row. Returns null for
 * EVERY failure mode — missing credentials (post-[clearCredentials][com.phonecompanion.app.data.SecureStorage.clearCredentials]
 * unpaired state), empty cache, `secret_ref`/kid mismatch, and auth-tag
 * failure — which the widget renders as the single [SecretWidget.MASKED]
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

class SecretWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SecretWidget()
}
