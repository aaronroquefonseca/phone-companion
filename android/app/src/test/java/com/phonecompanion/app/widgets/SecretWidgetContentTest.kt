package com.phonecompanion.app.widgets

import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import com.phonecompanion.app.data.WidgetCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fix batch B: per-instance [SecretWidget] content.
 *
 * The widget shows the cached row matching its Glance instance, falling
 * back to the first cached secret; resolution is fail-closed (null, which
 * the widget renders as [SecretWidget.MASKED]).
 */
class SecretWidgetContentTest {

    // Fixed vector shared with SecretCryptoTest: key 0x00..0x1f, nonce
    // 0xa0..0xab, AAD `dev-1 0x00 hello`, plaintext "hello secret".
    private val keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val ciphertextB64 = "jn0QQSrrcdoBF+KnfT0X7AnZ7CubwyCVijmu9A=="
    private val kid = "dev-1"

    private fun envelope(kid: String = this.kid): String =
        "{\"alg\":\"AES-256-GCM\",\"kid\":\"$kid\"," +
            "\"nonce\":\"oKGio6Slpqeoqaqr\",\"ciphertext\":\"$ciphertextB64\"}"

    private fun row(
        id: String,
        title: String = "Title $id",
        payloadJson: String = envelope(),
        secretRef: String? = kid,
    ) = WidgetCacheEntity(
        id = id,
        type = "secret",
        title = title,
        updatedAt = "now",
        payloadJson = payloadJson,
        secretRef = secretRef,
    )

    @Test
    fun `glanceKey unwraps app widget ids`() {
        assertEquals("42", glanceKey(AppWidgetId(42)))
    }

    @Test
    fun `glanceKey falls back to id string`() {
        val id = object : GlanceId {
            override fun toString(): String = "custom-id"
        }
        assertEquals("custom-id", glanceKey(id))
    }

    @Test
    fun `exact id match wins over first row`() {
        val rows = listOf(row("a"), row("b"), row("c"))
        assertEquals("b", selectRowForGlance(rows, "b")?.id)
    }

    @Test
    fun `no match falls back to first row`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals("a", selectRowForGlance(rows, "zzz")?.id)
    }

    @Test
    fun `empty cache selects nothing`() {
        assertNull(selectRowForGlance(emptyList(), "any"))
    }

    @Test
    fun `cleared credentials resolve to null so the widget shows MASKED`() {
        // Post-revoke / unpair: no key (or no id) must never decrypt.
        assertNull(resolveVisibleSecret(null, null, row("hello")))
        assertNull(resolveVisibleSecret(kid, null, row("hello")))
        assertNull(resolveVisibleSecret(null, keyB64, row("hello")))
        assertNull(resolveVisibleSecret("", "", row("hello")))
    }

    @Test
    fun `empty cache resolves to null`() {
        assertNull(resolveVisibleSecret(kid, keyB64, null))
    }

    @Test
    fun `secret ref mismatch resolves to null`() {
        assertNull(resolveVisibleSecret("dev-2", keyB64, row("hello", secretRef = "dev-1")))
    }

    @Test
    fun `kid mismatch resolves to null`() {
        val mismatched = row("hello", payloadJson = envelope(kid = "dev-2"), secretRef = null)
        assertNull(resolveVisibleSecret(kid, keyB64, mismatched))
    }

    @Test
    fun `matching row decrypts to plaintext`() {
        assertEquals("hello secret", resolveVisibleSecret(kid, keyB64, row("hello")))
    }

    @Test
    fun `wrong key resolves to null`() {
        val wrong = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 0x7F })
        assertNull(resolveVisibleSecret(kid, wrong, row("hello")))
    }
}
