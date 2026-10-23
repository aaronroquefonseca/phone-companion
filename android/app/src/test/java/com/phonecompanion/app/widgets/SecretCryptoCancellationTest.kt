package com.phonecompanion.app.widgets

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fix batch B: coroutine cancellation must never be swallowed by the
 * secret-decrypt fail-closed mapping. [SecretCrypto.runCipherOrNull] (the
 * production cipher guard) rethrows [CancellationException] while every
 * other failure still maps to null.
 */
class SecretCryptoCancellationTest {

    @Test
    fun `cipher guard rethrows cancellation`() {
        try {
            SecretCrypto.runCipherOrNull { throw CancellationException("stop") }
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // Expected: cancellation unwinds the caller, never MASKED.
        }
    }

    @Test
    fun `cipher guard still maps ordinary failures to null`() {
        assertNull(SecretCrypto.runCipherOrNull { throw RuntimeException("boom") })
        assertNull(
            SecretCrypto.runCipherOrNull {
                throw javax.crypto.AEADBadTagException("tag mismatch")
            },
        )
    }

    @Test
    fun `cipher guard passes successes through`() {
        val bytes = byteArrayOf(1, 2, 3)
        org.junit.Assert.assertArrayEquals(
            bytes,
            SecretCrypto.runCipherOrNull { bytes },
        )
    }

    @Test
    fun `envelope decrypt stays fail-closed on garbage input`() {
        assertNull(
            SecretCrypto.decryptSecretEnvelope(
                envelopeJson = "not json",
                deviceKeyBase64 = "also-not-a-key",
                expectedKid = "dev-1",
                widgetId = "hello",
            ),
        )
    }
}
