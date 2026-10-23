package com.phonecompanion.app.widgets

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException

/**
 * Per-device secret decryption (P10 SECRET ROUND-TRIP).
 *
 * Wire contract: docs/api-contract.md "Encrypted envelope for `secret`
 * widgets". AES-256-GCM, fresh 96-bit nonce per message, envelope JSON
 * `{alg,kid,nonce,ciphertext}` with RFC 4648 §4 base64. Plaintext is the
 * UTF-8 bytes of the secret string (empty legal). AAD is exactly
 * `UTF-8(kid) || 0x00 || UTF-8(binding)` with binding == widget id.
 *
 * Oracle policy: EVERY failure maps to null (the widget shows the single
 * "needs refresh" masked state). Callers must not branch on failure kinds
 * and must not log failure details — kid-mismatch, unknown alg, bad
 * base64, auth-tag failure, and non-UTF-8 output are indistinguishable to
 * the user and to logcat. Never log plaintext, keys, nonces, or
 * ciphertexts. Rotation (re-pair) makes cached envelopes undecryptable by
 * design: the widget shows needs-refresh until the next poll fetches a
 * freshly-encrypted envelope.
 */
object SecretCrypto {

    const val ALGORITHM = "AES-256-GCM"
    const val KEY_SIZE_BYTES = 32
    const val NONCE_SIZE_BYTES = 12
    const val GCM_TAG_BITS = 128

    /**
     * Builds the exact AES-GCM AAD for (kid, binding): UTF-8(kid) || 0x00
     * || UTF-8(binding). Exported for cross-check tests; byte-identical to
     * backend/crypto.BuildAAD.
     */
    fun buildAad(kid: String, binding: String): ByteArray {
        val kidBytes = kid.toByteArray(Charsets.UTF_8)
        val bindingBytes = binding.toByteArray(Charsets.UTF_8)
        val out = ByteArray(kidBytes.size + 1 + bindingBytes.size)
        System.arraycopy(kidBytes, 0, out, 0, kidBytes.size)
        out[kidBytes.size] = 0x00
        System.arraycopy(bindingBytes, 0, out, kidBytes.size + 1, bindingBytes.size)
        return out
    }

    /**
     * Decrypts a secret-widget payload envelope.
     *
     * @param envelopeJson the widget payload JSON (the P5 envelope object).
     * @param deviceKeyBase64 base64 (std, padded) 256-bit per-device key
     * from [com.phonecompanion.app.data.SecureStorage].
     * @param expectedKid the device id that must match both the envelope
     * `kid` and the cached `secret_ref`; any mismatch fails closed.
     * @param widgetId the widget id used as the AAD binding.
     * @return the secret string, or null for EVERY failure mode
     * (single needs-refresh outcome — no oracle).
     */
    fun decryptSecretEnvelope(
        envelopeJson: String?,
        deviceKeyBase64: String?,
        expectedKid: String?,
        widgetId: String?,
    ): String? {
        try {
            if (envelopeJson.isNullOrBlank()) return null
            if (deviceKeyBase64.isNullOrBlank()) return null
            if (expectedKid.isNullOrBlank()) return null
            if (widgetId.isNullOrBlank()) return null

            val alg = extractField(envelopeJson, "alg") ?: return null
            if (alg != ALGORITHM) return null
            val kid = extractField(envelopeJson, "kid") ?: return null
            if (kid != expectedKid) return null
            val nonceB64 = extractField(envelopeJson, "nonce") ?: return null
            val ctB64 = extractField(envelopeJson, "ciphertext") ?: return null

            val keyBytes = try {
                Base64.getDecoder().decode(deviceKeyBase64.trim())
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (keyBytes.size != KEY_SIZE_BYTES) return null
            val nonce = try {
                Base64.getDecoder().decode(nonceB64.trim())
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (nonce.size != NONCE_SIZE_BYTES) return null
            val sealed = try {
                Base64.getDecoder().decode(ctB64.trim())
            } catch (e: IllegalArgumentException) {
                return null
            }
            // GCM tag is 16 bytes; shorter inputs can never authenticate.
            if (sealed.size < 16) return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.updateAAD(buildAad(kid, widgetId))
            val plainBytes = runCipherOrNull { cipher.doFinal(sealed) }
                ?: return null
            return strictUtf8(plainBytes) ?: return null
        } catch (e: CancellationException) {
            // Coroutine cancellation is never a decrypt failure: rethrow so
            // structured concurrency keeps working (no oracle change — this
            // path never maps to the masked state, it unwinds the caller).
            throw e
        } catch (e: Exception) {
            // Any unexpected failure (bad Cipher provider, etc.): same null.
            return null
        }
    }

    /**
     * Runs a single cipher operation, mapping EVERY failure to null — except
     * coroutine cancellation, which is rethrown. Covers `doFinal`
     * (AEADBadTagException on wrong key / transplanted ciphertext / tampered
     * bytes, plus any provider error): the single needs-refresh outcome.
     */
    internal fun runCipherOrNull(block: () -> ByteArray): ByteArray? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Strict UTF-8 decode: malformed output fails closed (null). */
    private fun strictUtf8(bytes: ByteArray): String? {
        return try {
            val decoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val cb: CharBuffer = decoder.decode(ByteBuffer.wrap(bytes))
            cb.toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /**
     * Minimal strict-ish JSON string-field extractor for the flat envelope.
     * Returns null when the field is absent or not a JSON string. Unknown
     * fields are ignored (forward compatibility); missing/typed-wrong
     * required fields fail closed.
     */
    private fun extractField(json: String, field: String): String? {
        // Matches "field" : "value" with no escaped-quote support beyond
        // what the backend emits (kids, nonces, ciphertexts, alg are all
        // [A-Za-z0-9+/=_-] without quotes or backslashes).
        val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }
}
