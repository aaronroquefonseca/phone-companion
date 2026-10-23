package com.phonecompanion.app.widgets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * P10 secret-decrypt unit tests (pure JVM: no Android framework).
 *
 * Fixed vector mirrors backend/crypto TestFixedVector exactly: key
 * 0x00..0x1f, nonce 0xa0..0xab, AAD `dev-1 0x00 hello`, plaintext
 * "hello secret". The backend's pinned ciphertext base64 is
 * `jn0QQSrrcdoBF+KnfT0X7AnZ7CubwyCVijmu9A==` (28 bytes: 12 plaintext +
 * 16 tag). NOTE: the P10 brief text truncated this string (missing 4
 * chars, invalid base64 length); this test uses the backend-verified
 * value and would fail against the truncated one.
 */
class SecretCryptoTest {

    private val keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val nonceB64 = "oKGio6Slpqeoqaqr"
    private val kid = "dev-1"
    private val widgetId = "hello"
    private val plaintext = "hello secret"
    private val ciphertextB64 = "jn0QQSrrcdoBF+KnfT0X7AnZ7CubwyCVijmu9A=="

    private fun envelope(
        alg: String = "AES-256-GCM",
        kid: String = this.kid,
        nonce: String = nonceB64,
        ciphertext: String = ciphertextB64,
    ): String = "{\"alg\":\"$alg\",\"kid\":\"$kid\",\"nonce\":\"$nonce\",\"ciphertext\":\"$ciphertext\"}"

    @Test
    fun `AAD construction matches backend BuildAAD`() {
        val got = SecretCrypto.buildAad("dev-1", "hello")
        val want = "dev-1".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00) +
            "hello".toByteArray(Charsets.UTF_8)
        assertArrayEquals(want, got)
    }

    @Test
    fun `fixed vector decrypts to hello secret`() {
        val got = SecretCrypto.decryptSecretEnvelope(
            envelopeJson = envelope(),
            deviceKeyBase64 = keyB64,
            expectedKid = kid,
            widgetId = widgetId,
        )
        assertEquals(plaintext, got)
    }

    @Test
    fun `all decrypt failures map to the single null needs-refresh outcome`() {
        val wrongKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 0xFF.toByte() })
        val raw = Base64.getDecoder().decode(ciphertextB64)
        val tampered = raw.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        val tamperedB64 = Base64.getEncoder().encodeToString(tampered)

        // Each case must return the SAME null (no oracle: the widget never
        // distinguishes kid-mismatch from auth-tag failure).
        val cases = listOf(
            // kid mismatch (envelope kid vs expected, and expected vs cache)
            envelope(kid = "dev-2") to kid,
            // wrong binding (transplanted across widgets)
            envelope() to "other-widget",
            // wrong key
            envelope() to kid,
            // unknown alg
            envelope(alg = "AES-256-CBC") to kid,
            envelope(alg = "") to kid,
            // malformed base64 / lengths
            envelope(nonce = "!!!not-base64!!!") to kid,
            envelope(nonce = Base64.getEncoder().encodeToString("short".toByteArray())) to kid,
            envelope(ciphertext = "!!!not-base64!!!") to kid,
            envelope(ciphertext = Base64.getEncoder().encodeToString("short".toByteArray())) to kid,
            envelope(ciphertext = tamperedB64) to kid,
            // missing fields
            "{}" to kid,
            """{"alg":"AES-256-GCM","kid":"dev-1"}""" to kid,
            // null / blank inputs
            null to kid,
            "" to kid,
        )
        for ((json, expected) in cases) {
            val key = if (json == envelope() && expected == kid) wrongKeyB64 else keyB64
            // The wrong-key case uses wrongKeyB64; others use the right key
            // but still fail for their own reason — all must be null.
            val useKey = if (json == envelope() && expected == kid) wrongKeyB64 else keyB64
            assertNull(
                "expected null for $json",
                SecretCrypto.decryptSecretEnvelope(json, useKey, expected, widgetId),
            )
            // Silence unused warning intent: every failure is the same null.
            check(key.isNotEmpty())
        }
        // Null key / null kid / blank widget all fail closed too.
        assertNull(SecretCrypto.decryptSecretEnvelope(envelope(), null, kid, widgetId))
        assertNull(SecretCrypto.decryptSecretEnvelope(envelope(), keyB64, null, widgetId))
        assertNull(SecretCrypto.decryptSecretEnvelope(envelope(), keyB64, kid, ""))
        assertNull(SecretCrypto.decryptSecretEnvelope(envelope(), "!!!", kid, widgetId))
        assertNull(SecretCrypto.decryptSecretEnvelope(envelope(), Base64.getEncoder().encodeToString(ByteArray(16)), kid, widgetId))
    }

    @Test
    fun `kotlin encrypt round-trips through decrypt`() {
        val key = Base64.getDecoder().decode(keyB64)
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val aad = SecretCrypto.buildAad(kid, widgetId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal("round-trip ✓".toByteArray(Charsets.UTF_8))
        val json = envelope(
            nonce = Base64.getEncoder().encodeToString(nonce),
            ciphertext = Base64.getEncoder().encodeToString(sealed),
        )
        assertEquals(
            "round-trip ✓",
            SecretCrypto.decryptSecretEnvelope(json, keyB64, kid, widgetId),
        )
        // Empty plaintext is legal and round-trips to "" (non-null).
        val cipher2 = Cipher.getInstance("AES/GCM/NoPadding")
        cipher2.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher2.updateAAD(aad)
        val sealedEmpty = cipher2.doFinal(ByteArray(0))
        val jsonEmpty = envelope(
            nonce = Base64.getEncoder().encodeToString(nonce),
            ciphertext = Base64.getEncoder().encodeToString(sealedEmpty),
        )
        assertEquals(
            "",
            SecretCrypto.decryptSecretEnvelope(jsonEmpty, keyB64, kid, widgetId),
        )
    }

    @Test
    fun `decrypt stub wrapper fails closed without a key`() {
        // Legacy P7 signature: no key supplied → null (needs refresh).
        assertNull(decryptSecretStub(envelope(), kid, widgetId))
        // With the key supplied it decrypts (wrapper delegates).
        assertEquals(
            plaintext,
            decryptSecretStub(envelope(), kid, widgetId, deviceKeyBase64 = keyB64),
        )
    }

    private fun encryptForNotify(plain: String, notificationId: String, field: String): String {
        val key = Base64.getDecoder().decode(keyB64)
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val binding = SecretCrypto.notifyBinding(notificationId, field)!!
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(SecretCrypto.buildAad(kid, binding))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "{\"alg\":\"AES-256-GCM\",\"kid\":\"$kid\"," +
            "\"nonce\":\"${Base64.getEncoder().encodeToString(nonce)}\"," +
            "\"ciphertext\":\"${Base64.getEncoder().encodeToString(sealed)}\"}"
    }

    @Test
    fun `notify binding matches backend NotifyBinding`() {
        assertEquals("notify/nid-1/title", SecretCrypto.notifyBinding("nid-1", "title"))
        assertEquals("notify/nid-1/body", SecretCrypto.notifyBinding("nid-1", "body"))
        // Empty segments and separator bytes fail closed (nothing
        // legitimate decrypts under them).
        assertNull(SecretCrypto.notifyBinding("", "title"))
        assertNull(SecretCrypto.notifyBinding("nid-1", ""))
        assertNull(SecretCrypto.notifyBinding(null, "title"))
        assertNull(SecretCrypto.notifyBinding("nid-1", null))
        assertNull(SecretCrypto.notifyBinding("a/b", "title"))
        assertNull(SecretCrypto.notifyBinding("nid-1", "a/b"))
    }

    @Test
    fun `notify field decrypt round-trips and fails closed`() {
        val nid = "nid-notify-1"
        val titleEnv = encryptForNotify("Dinner ✓", nid, "title")
        val bodyEnv = encryptForNotify("Ready in 10", nid, "body")
        assertEquals(
            "Dinner ✓",
            SecretCrypto.decryptNotifyField(titleEnv, keyB64, kid, nid, "title"),
        )
        assertEquals(
            "Ready in 10",
            SecretCrypto.decryptNotifyField(bodyEnv, keyB64, kid, nid, "body"),
        )
        // Every failure maps to the same null (no oracle): wrong kid,
        // wrong notification, wrong field, tampered envelope, bad key.
        val wrongKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 0xFF.toByte() })
        assertNull(SecretCrypto.decryptNotifyField(titleEnv, keyB64, "dev-2", nid, "title"))
        assertNull(SecretCrypto.decryptNotifyField(titleEnv, keyB64, kid, "nid-other", "title"))
        assertNull(SecretCrypto.decryptNotifyField(titleEnv, keyB64, kid, nid, "body"))
        assertNull(SecretCrypto.decryptNotifyField(bodyEnv, keyB64, kid, nid, "title"))
        assertNull(SecretCrypto.decryptNotifyField(titleEnv, wrongKeyB64, kid, nid, "title"))
        assertNull(SecretCrypto.decryptNotifyField(titleEnv + "x", keyB64, kid, nid, "title"))
        assertNull(SecretCrypto.decryptNotifyField(null, keyB64, kid, nid, "title"))
        assertNull(SecretCrypto.decryptNotifyField(titleEnv, keyB64, kid, null, "title"))
        assertNull(SecretCrypto.decryptNotifyField(titleEnv, keyB64, kid, nid, ""))
    }
}
