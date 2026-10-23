package com.phonecompanion.app.work

import com.phonecompanion.app.data.WidgetEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fix batch B: post-revoke poll handling.
 *
 * HTTP 401/403 from the widgets poll is the revoked-credential signal
 * (see `docs/api-contract.md`); [WidgetPoller.handleRevoked] then clears
 * credentials, drops `widget_cache`, and pushes fail-closed widget states.
 * The Android-bound reset itself needs a device, so these JVM tests pin
 * the pure decision + cache-mapping pieces it is built on.
 */
class WidgetPollerTest {

    @Test
    fun `revoked credential codes are exactly 401 and 403`() {
        assertTrue(WidgetPoller.isRevokedHttpCode(401))
        assertTrue(WidgetPoller.isRevokedHttpCode(403))
    }

    @Test
    fun `other codes are not revocation`() {
        for (code in listOf(200, 201, 304, 400, 404, 409, 410, 422, 500)) {
            assertFalse("code $code", WidgetPoller.isRevokedHttpCode(code))
        }
    }

    @Test
    fun `envelope maps to cache entity with serialized payload`() {
        val entity = WidgetPoller.toCacheEntity(
            WidgetEnvelope(
                id = "w1",
                type = "text",
                title = "Hello",
                updatedAt = "2026-01-01T00:00:00Z",
                payload = mapOf("body" to "hi"),
                secretRef = null,
            ),
        )
        assertEquals("w1", entity.id)
        assertEquals("text", entity.type)
        assertEquals("Hello", entity.title)
        assertEquals("""{"body":"hi"}""", entity.payloadJson)
        assertEquals(null, entity.secretRef)
    }

    @Test
    fun `secret envelope keeps its kid binding`() {
        val payload = mapOf<String, Any?>(
            "alg" to "AES-256-GCM",
            "kid" to "dev-1",
            "nonce" to "n",
            "ciphertext" to "c",
        )
        val entity = WidgetPoller.toCacheEntity(
            WidgetEnvelope(
                id = "s1",
                type = "secret",
                title = "Secret",
                updatedAt = "now",
                payload = payload,
                secretRef = "dev-1",
            ),
        )
        assertEquals("dev-1", entity.secretRef)
        assertTrue(entity.payloadJson.contains("\"kid\":\"dev-1\""))
    }

    @Test
    fun `batch split drops every null payload and keeps an empty cache write`() {
        val allNull = WidgetPoller.toCacheEntities(
            listOf(
                WidgetEnvelope(
                    id = "ghost",
                    type = "text",
                    title = "Ghost",
                    updatedAt = "now",
                    payload = null,
                ),
            ),
        )
        assertEquals(1, allNull.dropped)
        assertTrue(allNull.kept.isEmpty())

        val empty = WidgetPoller.toCacheEntities(emptyList())
        assertEquals(0, empty.dropped)
        assertTrue(empty.kept.isEmpty())
    }

    @Test
    fun `batch split keeps every valid envelope with zero drops`() {
        val batch = WidgetPoller.toCacheEntities(
            listOf(
                WidgetEnvelope(
                    id = "w1",
                    type = "text",
                    title = "Hello",
                    updatedAt = "now",
                    payload = mapOf("body" to "hi"),
                ),
            ),
        )
        assertEquals(0, batch.dropped)
        assertEquals(listOf("w1"), batch.kept.map { it.id })
    }

    @Test
    fun `null payload refuses to serialize instead of storing a fallback`() {
        try {
            WidgetPoller.payloadToJson(null)
            fail("null payload must throw, not cache \"{}\"")
        } catch (e: IllegalArgumentException) {
            // Expected: fail fast instead of poisoning the cache.
        }
        assertEquals("{}", WidgetPoller.payloadToJson(emptyMap()))
    }
}
