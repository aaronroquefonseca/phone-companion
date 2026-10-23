package com.phonecompanion.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPayloadParserTest {

    @Test
    fun `text body parses and never leaks the envelope`() {
        val body = WidgetPayloadParser.parseTextBody("""{"body": "# Hello widget"}""")
        assertEquals("# Hello widget", body)
    }

    @Test
    fun `text body is bounded to 2000 chars`() {
        val long = "x".repeat(5000)
        val body = WidgetPayloadParser.parseTextBody("""{"body": "$long"}""")
        assertEquals("x".repeat(WidgetPayloadParser.TEXT_BODY_MAX), body)
    }

    @Test
    fun `text body missing or malformed yields null`() {
        assertNull(WidgetPayloadParser.parseTextBody("""{"nope": 1}"""))
        assertNull(WidgetPayloadParser.parseTextBody("""{"body": 42}"""))
        assertNull(WidgetPayloadParser.parseTextBody("not json"))
        assertNull(WidgetPayloadParser.parseTextBody(""))
        assertNull(WidgetPayloadParser.parseTextBody("[1, 2]"))
    }

    @Test
    fun `list items parse with done flags`() {
        val items = WidgetPayloadParser.parseListItems(
            """{"items": [
                {"id": "a", "text": "Milk", "done": true},
                {"id": "b", "text": "Eggs", "done": false},
                {"id": "c", "text": "Bread"}
            ]}""",
        )
        assertEquals(
            listOf(
                WidgetPayloadParser.ListItem("Milk", true),
                WidgetPayloadParser.ListItem("Eggs", false),
                WidgetPayloadParser.ListItem("Bread", false),
            ),
            items,
        )
    }

    @Test
    fun `list rows capped and labels bounded, bad rows skipped`() {
        val many = (1..20).joinToString(",") { """{"id": "$it", "text": "item $it", "done": false}""" }
        val items = WidgetPayloadParser.parseListItems("""{"items": [$many]}""")
        assertEquals(WidgetPayloadParser.LIST_MAX_ROWS, items!!.size)
        assertEquals("item 1", items.first().label)

        val longLabel = "y".repeat(500)
        val bounded = WidgetPayloadParser.parseListItems(
            """{"items": [{"id": "a", "text": "$longLabel", "done": false}]}""",
        )
        assertEquals("y".repeat(WidgetPayloadParser.LIST_LABEL_MAX), bounded!!.single().label)

        val skipped = WidgetPayloadParser.parseListItems(
            """{"items": [{"id": "a"}, {"id": "b", "text": "ok", "done": true}, 42, null]}""",
        )
        assertEquals(listOf(WidgetPayloadParser.ListItem("ok", true)), skipped)
    }

    @Test
    fun `list missing items yields empty, malformed yields null`() {
        assertEquals(emptyList<WidgetPayloadParser.ListItem>(), WidgetPayloadParser.parseListItems("""{}"""))
        assertEquals(emptyList<WidgetPayloadParser.ListItem>(), WidgetPayloadParser.parseListItems("""{"items": {}}"""))
        assertNull(WidgetPayloadParser.parseListItems("{{{"))
    }

    @Test
    fun `status parses state and detail`() {
        assertEquals(
            WidgetPayloadParser.Status("ok", "All systems go"),
            WidgetPayloadParser.parseStatus("""{"state": "ok", "detail": "All systems go"}"""),
        )
    }

    @Test
    fun `status unknown states normalize, detail bounded`() {
        assertEquals(
            "unknown",
            WidgetPayloadParser.parseStatus("""{"state": "bogus", "detail": "d"}""")!!.state,
        )
        // Case-insensitive known state.
        assertEquals(
            "warn",
            WidgetPayloadParser.parseStatus("""{"state": "Warn", "detail": "d"}""")!!.state,
        )
        val long = "z".repeat(500)
        assertEquals(
            "z".repeat(WidgetPayloadParser.STATUS_DETAIL_MAX),
            WidgetPayloadParser.parseStatus("""{"state": "alert", "detail": "$long"}""")!!.detail,
        )
    }

    @Test
    fun `status malformed or missing detail yields null`() {
        assertNull(WidgetPayloadParser.parseStatus("""{"state": "ok"}"""))
        assertNull(WidgetPayloadParser.parseStatus("nope"))
    }

    @Test
    fun `titles are bounded`() {
        val long = "t".repeat(500)
        assertEquals("t".repeat(WidgetPayloadParser.TITLE_MAX), WidgetPayloadParser.sanitizeTitle(long))
        assertTrue(WidgetPayloadParser.sanitizeTitle("Short").isNotEmpty())
    }
}
