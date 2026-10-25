package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetPayloadParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fix batch B: per-instance [CompanionWidget] secret content.
 *
 * Row selection is the shared [WidgetBindingResolver.resolve] (same as the
 * other types); titles are bounded by
 * [WidgetPayloadParser.sanitizeTitle]. Resolution is fail-closed (null,
 * which the widget renders as [CompanionWidget.MASKED]).
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
    fun `bound instance gets its own row via shared resolver`() {
        val rows = listOf(row("a"), row("b"), row("c"))
        assertEquals("b", WidgetBindingResolver.resolve(rows, "b")?.id)
    }

    @Test
    fun `unbound instance selects nothing`() {
        val rows = listOf(row("a"), row("b"))
        assertNull(WidgetBindingResolver.resolve(rows, null))
    }

    @Test
    fun `empty cache selects nothing`() {
        assertNull(WidgetBindingResolver.resolve(emptyList(), null))
        assertNull(WidgetBindingResolver.resolve(emptyList(), "a"))
    }

    @Test
    fun `title is sanitized like the other widgets`() {
        val long = "t".repeat(WidgetPayloadParser.TITLE_MAX + 50)
        assertEquals(
            WidgetPayloadParser.TITLE_MAX,
            WidgetPayloadParser.sanitizeTitle(long).length,
        )
        assertEquals("short", WidgetPayloadParser.sanitizeTitle("short"))
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
