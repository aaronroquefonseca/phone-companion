package com.phonecompanion.app.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFallbackCopyTest {

    @Test
    fun `never-paired copy points at pairing everywhere`() {
        val reason = WidgetFallbackCopy.Reason.NEVER_PAIRED
        assertEquals("Not paired yet", WidgetFallbackCopy.titleFor("List", reason))
        assertEquals(
            "Pair this phone to show server content.",
            WidgetFallbackCopy.bodyFor(reason),
        )
        assertEquals(
            WidgetFallbackCopy.Reason.NEVER_PAIRED,
            WidgetFallbackCopy.reasonFor(false),
        )
        assertEquals(
            WidgetFallbackCopy.Reason.LOAD_FAILED,
            WidgetFallbackCopy.reasonFor(true),
        )
    }

    @Test
    fun `load-failed copy keeps the widget label`() {
        val reason = WidgetFallbackCopy.Reason.LOAD_FAILED
        assertEquals("List", WidgetFallbackCopy.titleFor("List", reason))
        assertEquals("Nothing to show yet.", WidgetFallbackCopy.bodyFor(reason))
    }

    @Test
    fun `masked secret copy mentions masking`() {
        val masked = WidgetFallbackCopy.SECRET_MASKED
        assertTrue(masked.contains("hidden"))
        assertTrue(masked.contains("needs refresh"))
    }

    @Test
    fun `overflow hint only when items exceed the shown cap`() {
        assertNull(WidgetFallbackCopy.overflowHint(3, 3))
        assertNull(WidgetFallbackCopy.overflowHint(0, 0))
        assertEquals("+1 more", WidgetFallbackCopy.overflowHint(4, 3))
        assertEquals("+3 more", WidgetFallbackCopy.overflowHint(8, 5))
    }

    @Test
    fun `visible window caps at five with a hint`() {
        val items = (1..8).toList()
        val (visible, hint) = WidgetFallbackCopy.visibleWithOverflow(items)
        assertEquals(listOf(1, 2, 3, 4, 5), visible)
        assertEquals("+3 more", hint)
    }

    @Test
    fun `short lists show everything with no hint`() {
        val (visible, hint) = WidgetFallbackCopy.visibleWithOverflow(listOf("a", "b"))
        assertEquals(listOf("a", "b"), visible)
        assertNull(hint)
    }

    @Test
    fun `ellipsize caps with a marker and passes short text through`() {
        assertEquals("abc", WidgetFallbackCopy.ellipsize("abc", 10))
        assertEquals("ab…", WidgetFallbackCopy.ellipsize("abcdef", 3))
        assertEquals("", WidgetFallbackCopy.ellipsize("abcdef", 0))
        assertEquals("…", WidgetFallbackCopy.ellipsize("abcdef", 1))
    }
}
