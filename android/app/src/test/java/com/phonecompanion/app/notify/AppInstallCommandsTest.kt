package com.phonecompanion.app.notify

import com.phonecompanion.app.data.AppMoshi
import com.phonecompanion.app.data.CommandsAckRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poll-triggered command parsing (`GET /api/v1/commands`).
 *
 * Same discipline as the notification parser: lenient unknown kinds
 * (ignored, counted), strict known fields (malformed `app_install`
 * entries skipped with counts, never a crash, never content in logs —
 * these tests assert counts, never echo bodies).
 */
class AppInstallCommandsTest {

    private fun entry(
        pushId: String = "push-1",
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf(
        "type" to "app_install",
        "push_id" to pushId,
        "apk_url" to "https://example.com/app.apk",
        "sha256" to "a".repeat(64),
        "package" to "com.example.app",
        "version" to "2.1",
        "label" to "Beta",
    ) + extra

    private fun body(vararg entries: Any?): String {
        val sb = StringBuilder("{\"commands\":[")
        entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            when (e) {
                is Map<*, *> -> {
                    sb.append('{')
                    e.entries.forEachIndexed { j, (k, v) ->
                        if (j > 0) sb.append(',')
                        sb.append('"').append(k).append("\":")
                        appendJsonValue(sb, v)
                    }
                    sb.append('}')
                }
                is String -> sb.append('"').append(e).append('"')
                null -> sb.append("null")
                else -> sb.append(e.toString())
            }
        }
        return sb.append("]}").toString()
    }

    private fun appendJsonValue(sb: StringBuilder, v: Any?) {
        when (v) {
            is String -> sb.append('"').append(v).append('"')
            null -> sb.append("null")
            else -> sb.append(v.toString())
        }
    }

    @Test
    fun `valid entries parse in order`() {
        val parsed = AppInstallCommands.parseResponse(
            body(entry("push-1"), entry("push-2")),
        )
        assertFalse(parsed.syntaxError)
        assertEquals(2, parsed.valid.size)
        assertEquals("push-1", parsed.valid[0].pushId)
        assertEquals("push-2", parsed.valid[1].pushId)
        assertEquals(0, parsed.unknownKinds)
        assertEquals(0, parsed.malformed)
    }

    @Test
    fun `unknown kinds are ignored not crashed`() {
        val parsed = AppInstallCommands.parseResponse(
            body(
                entry("push-1"),
                mapOf("type" to "note", "title" to "hi"),
                mapOf("kind" to "alert", "body" to "x"),
                mapOf("title" to "no discriminator at all"),
                entry("push-9", mapOf("type" to 42)),
            ),
        )
        assertFalse(parsed.syntaxError)
        assertEquals(1, parsed.valid.size)
        assertEquals("push-1", parsed.valid[0].pushId)
        // Non-string discriminator cannot be an install: unknown, not crash.
        assertEquals(4, parsed.unknownKinds)
        assertEquals(0, parsed.malformed)
    }

    @Test
    fun `malformed app_install entries are skipped with counts`() {
        val parsed = AppInstallCommands.parseResponse(
            body(
                entry("ok-1"),
                entry("bad-sha", mapOf("sha256" to "zz")),
                entry("bad-url", mapOf("apk_url" to "http://example.com/a.apk")),
                entry("no-label", mapOf("label" to "   ")),
                entry("bad-pkg", mapOf("package" to "singleword")),
                "not-an-object",
            ),
        )
        assertFalse(parsed.syntaxError)
        assertEquals(listOf("ok-1"), parsed.valid.map { it.pushId })
        assertEquals(0, parsed.unknownKinds)
        assertEquals(5, parsed.malformed)
    }

    @Test
    fun `non-string values fail strict fields but tolerate optional ones`() {
        // Numeric push_id cannot validate as missing -> malformed.
        // Numeric version drops to absent -> still valid (optional).
        val parsed = AppInstallCommands.parseResponse(
            body(
                entry("num-id", mapOf("push_id" to 7)),
                entry("num-version", mapOf("version" to 2)),
            ),
        )
        assertFalse(parsed.syntaxError)
        assertEquals(listOf("num-version"), parsed.valid.map { it.pushId })
        assertEquals(1, parsed.malformed)
    }

    @Test
    fun `extra unknown fields are tolerated`() {
        val parsed = AppInstallCommands.parseResponse(
            body(entry("push-1", mapOf("future_field" to "anything", "n" to 3))),
        )
        assertFalse(parsed.syntaxError)
        assertEquals(1, parsed.valid.size)
    }

    @Test
    fun `blank body is empty not an error`() {
        val parsed = AppInstallCommands.parseResponse("   ")
        assertFalse(parsed.syntaxError)
        assertTrue(parsed.valid.isEmpty())
        assertEquals(0, parsed.unknownKinds)
        assertEquals(0, parsed.malformed)
    }

    @Test
    fun `garbage and wrong envelopes are syntax errors`() {
        assertTrue(AppInstallCommands.parseResponse("not json{{").syntaxError)
        assertTrue(AppInstallCommands.parseResponse("[1,2]").syntaxError)
        assertTrue(AppInstallCommands.parseResponse("{\"widgets\":[]}").syntaxError)
        assertTrue(AppInstallCommands.parseResponse("{\"commands\":{\"a\":1}}").syntaxError)
    }

    @Test
    fun `never throws on hostile bodies`() {
        // Must not throw: huge, nested, null entries.
        val hostile = "{\"commands\":[null,{\"type\":\"app_install\"}," +
            "{\"type\":\"app_install\",\"push_id\":\"" + "p".repeat(5000) + "\"}]," +
            "\"x\":[[[[1]]]]}"
        val parsed = AppInstallCommands.parseResponse(hostile)
        assertFalse(parsed.syntaxError)
        assertTrue(parsed.valid.isEmpty())
        // null entry + typeless install + oversized push_id: all malformed.
        assertEquals(3, parsed.malformed + parsed.unknownKinds)
    }

    @Test
    fun `ack request serializes the push_ids wire shape`() {
        val adapter = AppMoshi.moshi.adapter(CommandsAckRequest::class.java)
        val json = adapter.toJson(CommandsAckRequest(listOf("push-1", "push-2")))
        assertTrue("ack must carry push_ids, was: $json", json.contains("\"push_ids\""))
        assertTrue(json.contains("push-1"))
        assertTrue(json.contains("push-2"))
    }
}
