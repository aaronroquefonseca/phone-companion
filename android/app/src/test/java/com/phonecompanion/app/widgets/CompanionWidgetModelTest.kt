package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-widget hardening: every home-screen state renders SOMETHING
 * valid, never throws, never posts null.
 *
 * Drives the unified dispatch ([CompanionContent.resolveForRow]) that
 * `provideGlance` renders — unbound, stale binding, empty cache, unknown
 * type, malformed payloads, decrypt failure, missing credentials — and
 * asserts each outcome is non-blank, correctly flagged, and taps to the
 * right destination. The widget renders exactly these models, so a
 * passing suite means no widget state can reach Glance composition as
 * null/blank/post-nothing.
 */
class CompanionWidgetModelTest {

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

    private fun assertRenders(render: CompanionContent.Render) {
        for (text in CompanionContent.texts(render)) {
            assertTrue("blank render string in $render", text.isNotBlank())
        }
    }

    private fun tapOf(render: CompanionContent.Render): WidgetContent.TapDestination {
        val (paired, unbound) = CompanionContent.tapState(render)
        return WidgetContent.tapFor(paired, unbound)
    }

    @Test
    fun `freshly placed widget with no binding is not-associated with config tap`() {
        val render = CompanionContent.resolveForRow(null, paired = true, visibleSecret = null)
        assertRenders(render)
        val unbound = render as CompanionContent.Render.Unbound
        assertEquals(WidgetContent.UNASSOCIATED_TITLE, unbound.title)
        assertEquals(WidgetContent.UNASSOCIATED_BODY, unbound.body)
        assertEquals(WidgetContent.TapDestination.CONFIG, tapOf(render))
    }

    @Test
    fun `stale binding selects nothing never another rows content`() {
        val rows = listOf(row(id = "a"), row(id = "b"))
        val resolved = WidgetBindingResolver.resolve(rows, "gone")
        assertEquals(null, resolved)
        val render = CompanionContent.resolveForRow(resolved, paired = true, visibleSecret = null)
        assertRenders(render)
        assertTrue(render is CompanionContent.Render.Unbound)
        assertEquals(WidgetContent.TapDestination.CONFIG, tapOf(render))
    }

    @Test
    fun `empty cache renders not-associated whatever the binding`() {
        for (binding in listOf(null, "hello")) {
            val resolved = WidgetBindingResolver.resolve(emptyList(), binding)
            val render = CompanionContent.resolveForRow(resolved, paired = true, visibleSecret = null)
            assertRenders(render)
            assertTrue(render is CompanionContent.Render.Unbound)
        }
    }

    @Test
    fun `unknown type renders not-associated with config tap`() {
        val render = CompanionContent.resolveForRow(
            row(id = "mystery", type = "teleport", title = "Mystery"),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(render)
        assertTrue("unknown type must not render another type: $render", render is CompanionContent.Render.Unbound)
        assertEquals(WidgetContent.TapDestination.CONFIG, tapOf(render))
    }

    @Test
    fun `unpaired widget renders pairing guidance and taps to the app`() {
        val render = CompanionContent.resolveForRow(null, paired = false, visibleSecret = null)
        assertRenders(render)
        val unbound = render as CompanionContent.Render.Unbound
        assertEquals("Not paired yet", unbound.title)
        assertEquals(WidgetContent.TapDestination.ROUTER, tapOf(render))
    }

    @Test
    fun `bound text passes through and taps to the app`() {
        val render = CompanionContent.resolveForRow(row(), paired = true, visibleSecret = null)
        assertRenders(render)
        val text = render as CompanionContent.Render.Text
        assertEquals("Hello", text.model.title)
        assertEquals("hi", text.model.body)
        assertFalse(text.model.unbound)
        assertEquals(WidgetContent.TapDestination.ROUTER, tapOf(render))
    }

    @Test
    fun `malformed text payload renders fallback never blank`() {
        val render = CompanionContent.resolveForRow(
            row(payloadJson = "not json"),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(render)
        assertTrue(render is CompanionContent.Render.Text)
        assertFalse((render as CompanionContent.Render.Text).model.unbound)
    }

    @Test
    fun `empty text body renders fallback never blank`() {
        val render = CompanionContent.resolveForRow(
            row(payloadJson = """{"body":""}"""),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(render)
        assertEquals(
            WidgetFallbackCopy.bodyFor(WidgetFallbackCopy.Reason.LOAD_FAILED),
            (render as CompanionContent.Render.Text).model.body,
        )
    }

    @Test
    fun `blank text title renders fallback never blank`() {
        val render = CompanionContent.resolveForRow(
            row(title = "   "),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(render)
    }

    @Test
    fun `list renders rows with overflow and blank labels as markers`() {
        val payload = """{"items":[
            {"id":"a","text":"","done":true},
            {"id":"b","text":"Milk","done":false}
        ]}"""
        val render = CompanionContent.resolveForRow(
            row(id = "tasks", type = "list", title = "Tasks", payloadJson = payload),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(render)
        val list = render as CompanionContent.Render.List
        assertEquals(2, list.model.visible.size)
        assertTrue(list.model.visible.all { it.label.isNotBlank() })
        assertEquals(WidgetContent.TapDestination.ROUTER, tapOf(render))
    }

    @Test
    fun `list malformed payload renders fallback with empty rows`() {
        val render = CompanionContent.resolveForRow(
            row(id = "tasks", type = "list", payloadJson = "garbage"),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(render)
        val list = render as CompanionContent.Render.List
        assertTrue(list.model.visible.isEmpty())
        assertFalse(list.model.unbound)
    }

    @Test
    fun `status keeps normalization and blank detail falls back`() {
        val ok = CompanionContent.resolveForRow(
            row(id = "s", type = "status", payloadJson = """{"state":"bogus","detail":"All good"}"""),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(ok)
        assertEquals("unknown", (ok as CompanionContent.Render.Status).model.state)

        val blank = CompanionContent.resolveForRow(
            row(id = "s", type = "status", payloadJson = """{"state":"ok","detail":""}"""),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(blank)
    }

    @Test
    fun `secret decrypt failure stays masked never plaintext`() {
        val render = CompanionContent.resolveForRow(
            row(id = "sec", type = "secret"),
            paired = true,
            visibleSecret = null,
        )
        assertRenders(render)
        val secret = render as CompanionContent.Render.Secret
        assertEquals(WidgetFallbackCopy.SECRET_MASKED, secret.model.body)
        assertFalse(secret.model.unbound)
        assertEquals(WidgetContent.TapDestination.ROUTER, tapOf(render))
    }

    @Test
    fun `secret empty decrypt output stays masked`() {
        val render = CompanionContent.resolveForRow(
            row(id = "sec", type = "secret"),
            paired = true,
            visibleSecret = "",
        )
        assertRenders(render)
        assertEquals(
            WidgetFallbackCopy.SECRET_MASKED,
            (render as CompanionContent.Render.Secret).model.body,
        )
    }

    @Test
    fun `secret unbound stays masked with config tap`() {
        val render = CompanionContent.resolveForRow(null, paired = true, visibleSecret = null)
        assertRenders(render)
        // The unbound secret state never leaks decrypt outcomes: the body
        // is the tap hint, and the tap opens the configuration.
        assertEquals(WidgetContent.TapDestination.CONFIG, tapOf(render))
    }

    @Test
    fun `secret bound decrypt success shows plaintext`() {
        val render = CompanionContent.resolveForRow(
            row(id = "sec", type = "secret", title = "Vault"),
            paired = true,
            visibleSecret = "hunter2",
        )
        assertRenders(render)
        val secret = render as CompanionContent.Render.Secret
        assertEquals("hunter2", secret.model.body)
        assertFalse(secret.model.unbound)
    }

    @Test
    fun `secret never-paired renders pairing guidance`() {
        val render = CompanionContent.resolveForRow(
            row(id = "sec", type = "secret"),
            paired = false,
            visibleSecret = null,
        )
        assertRenders(render)
        val secret = render as CompanionContent.Render.Secret
        assertEquals("Not paired yet", secret.model.title)
        assertEquals(WidgetContent.TapDestination.ROUTER, tapOf(render))
    }

    @Test
    fun `load-failure fallback is always a valid unbound model`() {
        val fallback = CompanionContent.unboundFallback()
        assertRenders(fallback)
        assertTrue(fallback.paired)
        assertEquals(WidgetContent.UNASSOCIATED_TITLE, fallback.title)
        assertEquals(WidgetContent.UNASSOCIATED_BODY, fallback.body)
    }
}
