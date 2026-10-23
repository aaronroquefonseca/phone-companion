package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetPayloadParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash-fix regression: every widget state renders SOMETHING valid.
 *
 * Drives the provideGlance-equivalent load logic ([WidgetContent] models
 * over cache rows + binding + pairing/decrypt outcomes) against the
 * on-device failure set — unbound instance, stale binding, empty cache,
 * blank/empty server values, malformed payloads, decrypt failure — and
 * asserts each model is non-blank, correctly flagged, and never throws.
 * The widgets render exactly these models, so a passing suite means no
 * widget state can reach Glance composition as null/blank/post-nothing.
 */
class WidgetContentTest {

    private fun row(
        id: String = "hello",
        type: String = "text",
        title: String = "Hello",
        payloadJson: String = """{"body":"hi"}""",
        secretRef: String? = null,
    ) = WidgetCacheEntity(
        id = id,
        type = type,
        title = title,
        updatedAt = "2026-10-23T00:00:00Z",
        payloadJson = payloadJson,
        secretRef = secretRef,
    )

    private fun assertRenders(title: String, body: String) {
        assertTrue("blank title: '$title'", title.isNotBlank())
        assertTrue("blank body: '$body'", body.isNotBlank())
    }

    @Test
    fun `unbound text instance renders not-associated and taps to config`() {
        for (widgetId in listOf(null, "gone")) {
            val model = WidgetContent.textModel(listOf(row()), widgetId, paired = true)
            assertRenders(model.title, model.body)
            assertTrue(model.unbound)
            assertEquals(WidgetContent.UNASSOCIATED_TITLE, model.title)
            assertEquals(WidgetContent.UNASSOCIATED_BODY, model.body)
        }
        assertEquals(
            WidgetContent.TapDestination.CONFIG,
            WidgetContent.tapFor(paired = true, unbound = true),
        )
    }

    @Test
    fun `bound text tap routes to the app not config`() {
        val model = WidgetContent.textModel(listOf(row()), "hello", paired = true)
        assertFalse(model.unbound)
        assertEquals(
            WidgetContent.TapDestination.ROUTER,
            WidgetContent.tapFor(model.paired, model.unbound),
        )
    }

    @Test
    fun `unpaired taps route to the app even when unbound`() {
        assertEquals(
            WidgetContent.TapDestination.ROUTER,
            WidgetContent.tapFor(paired = false, unbound = true),
        )
        val model = WidgetContent.textModel(listOf(row()), null, paired = false)
        assertRenders(model.title, model.body)
        assertEquals("Not paired yet", model.title)
    }

    @Test
    fun `empty cache renders not-associated for every type`() {
        val text = WidgetContent.textModel(emptyList(), "hello", paired = true)
        assertRenders(text.title, text.body)
        assertTrue(text.unbound)

        val list = WidgetContent.listModel(emptyList(), "tasks", paired = true)
        assertRenders(list.title, list.emptyHint)
        assertTrue(list.visible.isEmpty())
        assertTrue(list.unbound)

        val status = WidgetContent.statusModel(emptyList(), "state", paired = true)
        assertRenders(status.state, status.detail)
        assertTrue(status.unbound)
    }

    @Test
    fun `empty-string body renders fallback never blank`() {
        // `{"body":""}` parses to "" (not null): the empty-string case that
        // must never reach Glance Text as-is.
        val model = WidgetContent.textModel(
            listOf(row(payloadJson = """{"body":""}""")),
            "hello",
            paired = true,
        )
        assertRenders(model.title, model.body)
        assertFalse(model.unbound)
        assertEquals(WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED), model.body)
    }

    @Test
    fun `blank titles and malformed payloads render fallback copy`() {
        val blankTitle = WidgetContent.textModel(
            listOf(row(title = "   ")),
            "hello",
            paired = true,
        )
        assertRenders(blankTitle.title, blankTitle.body)

        val malformed = WidgetContent.textModel(
            listOf(row(payloadJson = "not json")),
            "hello",
            paired = true,
        )
        assertRenders(malformed.title, malformed.body)
        assertFalse(malformed.unbound)

        val missingBody = WidgetContent.textModel(
            listOf(row(payloadJson = "{}")),
            "hello",
            paired = true,
        )
        assertRenders(missingBody.title, missingBody.body)
    }

    @Test
    fun `valid text content passes through`() {
        val model = WidgetContent.textModel(listOf(row()), "hello", paired = true)
        assertEquals("Hello", model.title)
        assertEquals("hi", model.body)
    }

    @Test
    fun `list blank labels render as markers and overflow survives`() {
        val payload = """{"items":[
            {"id":"a","text":"","done":true},
            {"id":"b","text":"Milk","done":false}
        ]}"""
        val model = WidgetContent.listModel(
            listOf(row(id = "tasks", type = "list", title = "Tasks", payloadJson = payload)),
            "tasks",
            paired = true,
        )
        assertRenders(model.title, model.emptyHint)
        assertEquals(2, model.visible.size)
        assertTrue(model.visible.all { it.label.isNotBlank() })
        assertNull(model.overflow)
    }

    @Test
    fun `list overflow hint only when items exceed the cap`() {
        val items = (1..8).joinToString(",") { """{"id":"$it","text":"item $it","done":false}""" }
        val model = WidgetContent.listModel(
            listOf(row(id = "tasks", type = "list", title = "Tasks", payloadJson = """{"items":[$items]}""")),
            "tasks",
            paired = true,
        )
        assertEquals(WidgetFallbackCopy.LIST_MAX_SHOWN, model.visible.size)
        assertEquals("+3 more", model.overflow)
    }

    @Test
    fun `list malformed payload renders fallback with empty rows`() {
        val model = WidgetContent.listModel(
            listOf(row(id = "tasks", type = "list", payloadJson = "garbage")),
            "tasks",
            paired = true,
        )
        assertRenders(model.title, model.emptyHint)
        assertTrue(model.visible.isEmpty())
        assertNull(model.overflow)
        assertFalse(model.unbound)
    }

    @Test
    fun `status blank detail renders fallback never blank`() {
        val model = WidgetContent.statusModel(
            listOf(
                row(
                    id = "state",
                    type = "status",
                    title = "Server",
                    payloadJson = """{"state":"ok","detail":""}""",
                ),
            ),
            "state",
            paired = true,
        )
        assertRenders(model.state, model.detail)
        assertFalse(model.unbound)
    }

    @Test
    fun `status keeps state normalization`() {
        val model = WidgetContent.statusModel(
            listOf(
                row(
                    id = "state",
                    type = "status",
                    title = "Server",
                    payloadJson = """{"state":"bogus","detail":"All systems go"}""",
                ),
            ),
            "state",
            paired = true,
        )
        assertEquals("unknown", model.state)
        assertEquals("All systems go", model.detail)
    }

    @Test
    fun `secret decrypt failure stays masked never plaintext`() {
        val model = WidgetContent.secretModel(row(type = "secret"), neverPaired = false, visible = null)
        assertRenders(model.title, model.body)
        assertEquals(WidgetFallbackCopy.SECRET_MASKED, model.body)
        assertFalse(model.unbound)
    }

    @Test
    fun `secret empty decrypt output stays masked`() {
        // Empty plaintext is legal per the crypto contract but renders as
        // blank — fail closed to the masked line (no oracle either way).
        val model = WidgetContent.secretModel(row(type = "secret"), neverPaired = false, visible = "")
        assertEquals(WidgetFallbackCopy.SECRET_MASKED, model.body)
    }

    @Test
    fun `secret unbound stays masked with config tap`() {
        val model = WidgetContent.secretModel(null, neverPaired = false, visible = null)
        assertEquals(WidgetFallbackCopy.SECRET_MASKED, model.body)
        assertTrue(model.unbound)
        assertEquals(
            WidgetContent.TapDestination.CONFIG,
            WidgetContent.tapFor(model.paired, model.unbound),
        )
    }

    @Test
    fun `secret never-paired renders pairing guidance`() {
        val model = WidgetContent.secretModel(row(type = "secret"), neverPaired = true, visible = null)
        assertRenders(model.title, model.body)
        assertEquals("Not paired yet", model.title)
        assertEquals(
            WidgetContent.TapDestination.ROUTER,
            WidgetContent.tapFor(model.paired, model.unbound),
        )
    }

    @Test
    fun `nonBlank maps every blank to the fallback`() {
        assertEquals("f", WidgetContent.nonBlank(null, "f"))
        assertEquals("f", WidgetContent.nonBlank("", "f"))
        assertEquals("f", WidgetContent.nonBlank("   ", "f"))
        assertEquals("v", WidgetContent.nonBlank("v", "f"))
        // Long values pass through untouched (callers bound them already).
        val long = "x".repeat(WidgetPayloadParser.TITLE_MAX + 50)
        assertEquals(long, WidgetContent.nonBlank(long, "f"))
    }
}
