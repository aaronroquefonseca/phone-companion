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
 * Secret widget: fail-closed masked rendering.
 *
 * Distinct rendering: masked placeholder column — never plaintext on auth
 * failure. Decryption itself is Wave-2 (P10); this file only exposes the
 * [decryptSecretStub] hook with that TODO.
 */
class SecretWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val title = loadTitle(context)
        // Fail closed by design: without Wave-2 crypto the widget shows the
        // masked state, never plaintext, never a crash.
        val visible = decryptSecretStub(
            ciphertextEnvelopeJson = null,
            kid = null,
            widgetId = "secret",
        )
        provideContent {
            Column {
                Text(title)
                Text(visible ?: MASKED)
            }
        }
    }

    private suspend fun loadTitle(context: Context): String {
        return try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
            try {
                db.widgetCacheDao().getByType("secret").firstOrNull()?.title ?: FALLBACK_TITLE
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            FALLBACK_TITLE
        }
    }

    companion object {
        private const val DB_NAME = "phone-companion.db"
        private const val FALLBACK_TITLE = "Secret"
        private const val MASKED = "•••••• needs refresh"
    }
}

/**
 * Decrypt hook stub for Wave-2 (P10).
 *
 * TODO (Wave-2 P10): implement AES-256-GCM decrypt of the secret envelope
 * (alg/kid/nonce/ciphertext, AAD kid+widget id) using the stored per-device
 * key selected by [kid]; fail closed (null) on mismatch/auth failure.
 * Do NOT cache or log plaintext.
 */
fun decryptSecretStub(
    ciphertextEnvelopeJson: String?,
    kid: String?,
    widgetId: String,
): String? = null

class SecretWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SecretWidget()
}
