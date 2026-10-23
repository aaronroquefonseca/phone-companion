package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared chooser contract: human option lines, stable checked position,
 * fail-closed resolution. Used by both the Widgets-screen buttons and the
 * placement configuration, so both hosts behave identically.
 */
class WidgetChooserTest {

    private fun row(id: String, title: String = "t-$id") = WidgetCacheEntity(
        id = id,
        type = "text",
        title = title,
        updatedAt = "2026-01-10T10:00:00Z",
        payloadJson = """{"body":"$id"}""",
    )

    private val nowMs = 1_768_046_400_000L // 2026-01-10T12:00:00Z.

    @Test
    fun `options lead with not-associated then human row lines`() {
        val options = WidgetChooser.options(listOf(row("a", "Hello"), row("b", "World")), nowMs)
        assertEquals(3, options.size)
        assertNull(options[0].widgetId)
        assertEquals(WidgetChooser.NOT_ASSOCIATED_LABEL, options[0].line)
        assertEquals("a", options[1].widgetId)
        assertTrue(options[1].line, options[1].line.contains("Hello"))
        assertTrue(options[1].line, options[1].line.contains("a"))
        assertTrue(options[1].line, options[1].line.contains("2 hours ago"))
        // No raw Glance instance ids anywhere user-visible.
        for (option in options) {
            assertTrue(option.line, !option.line.contains("APPWIDGETID"))
            assertTrue(option.line, !option.line.contains("AppWidgetId"))
            assertTrue(option.line, !option.line.contains("glanceKey"))
        }
    }

    @Test
    fun `empty cache offers only not-associated`() {
        val options = WidgetChooser.options(emptyList(), nowMs)
        assertEquals(listOf(WidgetChooser.Option(null, "Not associated")), options)
        assertEquals(0, WidgetChooser.checkedIndex(emptyList(), null))
        assertNull(WidgetChooser.chosenId(emptyList(), 0))
    }

    @Test
    fun `checked index follows the pin stale pins check not-associated`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals(0, WidgetChooser.checkedIndex(rows, null))
        assertEquals(0, WidgetChooser.checkedIndex(rows, "gone"))
        assertEquals(1, WidgetChooser.checkedIndex(rows, "a"))
        assertEquals(2, WidgetChooser.checkedIndex(rows, "b"))
    }

    @Test
    fun `chosen id maps positions back fail-closed`() {
        val rows = listOf(row("a"), row("b"))
        assertNull(WidgetChooser.chosenId(rows, 0))
        assertEquals("a", WidgetChooser.chosenId(rows, 1))
        assertEquals("b", WidgetChooser.chosenId(rows, 2))
        assertNull(WidgetChooser.chosenId(rows, -1))
        assertNull(WidgetChooser.chosenId(rows, 3))
        assertNull(WidgetChooser.chosenId(rows, Int.MAX_VALUE))
        assertNull(WidgetChooser.chosenId(emptyList(), 1))
    }

    @Test
    fun `blank row titles render as untitled`() {
        val options = WidgetChooser.options(listOf(row("a", "   ")), nowMs)
        assertTrue(options[1].line, options[1].line.contains("Untitled"))
    }
}
