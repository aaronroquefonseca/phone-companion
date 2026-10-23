package com.phonecompanion.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parse-path regression test: proves the app can decode what the backend
 * actually serves on GET /api/v1/widgets.
 *
 * The [FULL_BODY] literal below is a verbatim capture of a real backend
 * response (backend `TestZZDumpWidgetListBody` fixture: text + status +
 * list-with-nested-objects/arrays + per-device secret envelope, id-sorted).
 * Every case parses through [AppMoshi] — the exact Moshi instance backing
 * [ApiClient]'s Retrofit converter — so the test and production parsing
 * cannot drift apart. If any case fails, THAT is the on-device bug
 * (HTTP 200 with a full body yet every widget falls back and the cache
 * stays empty: Retrofit/Moshi threw and the poll recorded a silent
 * TransportFailure).
 *
 * Lenient-forward-compatible philosophy: unknown fields and unknown widget
 * types must parse (callers ignore them), never crash.
 */
class WidgetEnvelopeParseTest {

    companion object {
        // Verbatim backend capture — do not "tidy": the point is byte-shape
        // fidelity with what the server emits (key order, nesting, escapes).
        const val FULL_BODY =
            """{"widgets":[{"id":"hello","type":"text","title":"Hello","updated_at":"2026-10-23T21:11:33Z","payload":{"body":"# Hello widget\n"}},{"id":"state","type":"status","title":"Server","updated_at":"2026-10-23T21:11:33Z","payload":{"state":"ok","detail":"All systems go","extra":{"region":"fra1","hosts":["a","b"]}}},{"id":"tasks","type":"list","title":"Tasks","updated_at":"2026-10-23T21:11:33Z","payload":{"items":[{"id":"a","text":"Buy milk","done":false},{"id":"b","text":"Eggs","done":true,"meta":{"aisle":3,"tags":["dairy","urgent"]}}],"meta":{"owner":"ops","tags":["home","weekly"]},"future_field":"tolerated"}},{"id":"vault","type":"secret","title":"Vault","updated_at":"2026-10-23T21:11:33Z","payload":{"alg":"AES-256-GCM","kid":"c7ddfde16e7f8a65b0eb4af4aafb8883","nonce":"CG9vBV1kKcgES4Wp","ciphertext":"4HAKt5cQWLcg0mxMvujwV90kT6whViRnbBMDMA=="},"secret_ref":"c7ddfde16e7f8a65b0eb4af4aafb8883"}]}"""
        const val DEVICE_ID = "c7ddfde16e7f8a65b0eb4af4aafb8883"
    }

    private fun parseAll(body: String): List<WidgetEnvelope> {
        val parsed = AppMoshi.widgetsResponseAdapter.fromJson(body)
        assertTrue("backend body must decode (parse failure = on-device fallback bug)", parsed != null)
        return parsed!!.widgets
    }

    @Test
    fun `full backend body parses all four types`() {
        val widgets = parseAll(FULL_BODY)

        assertEquals(4, widgets.size)
        assertEquals(listOf("hello", "state", "tasks", "vault"), widgets.map { it.id })
        assertEquals(listOf("text", "status", "list", "secret"), widgets.map { it.type })

        // text
        val text = widgets[0]
        assertEquals("Hello", text.title)
        assertEquals("2026-10-23T21:11:33Z", text.updatedAt)
        assertEquals("# Hello widget\n", text.payload!!["body"])
        assertNull("non-secret envelope carries no secret_ref", text.secretRef)

        // status, with tolerated nested extra object/array
        val status = widgets[1]
        assertEquals("ok", status.payload!!["state"])
        assertEquals("All systems go", status.payload!!["detail"])
        assertTrue((status.payload!!["extra"] as Map<*, *>)["region"] == "fra1")

        // list, with nested per-item objects/arrays and top-level extras
        val list = widgets[2]
        @Suppress("UNCHECKED_CAST")
        val items = list.payload!!["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals("Buy milk", items[0]["text"])
        assertEquals(false, items[0]["done"])
        @Suppress("UNCHECKED_CAST")
        val itemMeta = items[1]["meta"] as Map<String, Any?>
        assertEquals(3.0, itemMeta["aisle"])
        assertEquals(listOf("dairy", "urgent"), itemMeta["tags"])
        assertTrue(list.payload!!.containsKey("future_field"))

        // secret envelope: shape only, never decrypted here
        val secret = widgets[3]
        assertEquals(DEVICE_ID, secret.secretRef)
        assertEquals(DEVICE_ID, secret.payload!!["kid"])
        assertEquals("AES-256-GCM", secret.payload!!["alg"])
        assertTrue((secret.payload!!["nonce"] as String).isNotBlank())
        assertTrue((secret.payload!!["ciphertext"] as String).isNotBlank())
    }

    @Test
    fun `missing secret_ref parses as null`() {
        val body =
            """{"widgets":[{"id":"hello","type":"text","title":"Hello","updated_at":"2026-10-23T21:11:33Z","payload":{"body":"hi"}}]}"""
        val widgets = parseAll(body)
        assertEquals(1, widgets.size)
        assertNull(widgets.single().secretRef)
        assertEquals("hi", widgets.single().payload!!["body"])
    }

    @Test
    fun `null payload parses as null`() {
        val envelope = AppMoshi.envelopeAdapter.fromJson(
            """{"id":"x","type":"text","title":"T","updated_at":"2026-10-23T21:11:33Z","payload":null}""",
        )
        assertTrue(envelope != null)
        assertNull(envelope!!.payload)
    }

    @Test
    fun `missing payload key parses as null`() {
        val envelope = AppMoshi.envelopeAdapter.fromJson(
            """{"id":"x","type":"text","title":"T","updated_at":"2026-10-23T21:11:33Z"}""",
        )
        assertTrue(envelope != null)
        assertNull(envelope!!.payload)
    }

    @Test
    fun `empty widgets array parses to empty`() {
        val widgets = parseAll("""{"widgets":[]}""")
        assertTrue(widgets.isEmpty())
    }

    @Test
    fun `unknown extra fields are ignored`() {
        val body =
            """{"widgets":[{"id":"hello","type":"text","title":"Hello","updated_at":"2026-10-23T21:11:33Z","payload":{"body":"hi","v2only":{"nested":[1,2,3]}},"v2only":"tolerated"}],"etag":"abc","v2only":42}"""
        val widgets = parseAll(body)
        assertEquals(1, widgets.size)
        assertEquals("hello", widgets.single().id)
        assertEquals("hi", widgets.single().payload!!["body"])
    }

    @Test
    fun `unknown widget type parses and preserves type`() {
        val widgets = parseAll(
            """{"widgets":[{"id":"mystery","type":"teleport","title":"Mystery","updated_at":"2026-10-23T21:11:33Z","payload":{"beam":"up"}}]}""",
        )
        assertEquals(1, widgets.size)
        assertEquals("teleport", widgets.single().type)
        assertEquals("up", widgets.single().payload!!["beam"])
    }
}
