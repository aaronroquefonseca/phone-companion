package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.data.WidgetCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-instance selection: resolve / miss / stale / duplicate / upgrade.
 *
 * The contract: a pinned instance shows its row; an unpinned or
 * stale-miss instance shows the first row of its type (single-widget
 * users see zero change); an empty type selects nothing (the widget's
 * genuine fallback); duplicates are allowed; a fresh upgrade (no bindings
 * yet) behaves exactly like unpinned.
 */
class WidgetBindingResolverTest {

    private fun row(id: String, type: String = "text") = WidgetCacheEntity(
        id = id,
        type = type,
        title = "t-$id",
        updatedAt = "2026-10-23T00:00:00Z",
        payloadJson = """{"body": "$id"}""",
    )

    private fun binding(
        key: String,
        widgetId: String?,
        provider: String = "com.phonecompanion.app.widgets.TextWidget",
    ) = WidgetBindingEntity(
        providerClass = provider,
        glanceKey = key,
        widgetType = "text",
        widgetId = widgetId,
    )

    @Test
    fun `resolve finds the pinned row`() {
        val rows = listOf(row("a"), row("b"), row("c"))
        assertEquals("b", WidgetBindingResolver.resolve(rows, "b")!!.id)
    }

    @Test
    fun `miss with no binding defaults to the first row`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals("a", WidgetBindingResolver.resolve(rows, null)!!.id)
    }

    @Test
    fun `stale miss falls back to the first row with a was-hint`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals("a", WidgetBindingResolver.resolve(rows, "gone")!!.id)
        assertEquals("was-gone", WidgetBindingResolver.staleHint(rows, "gone"))
    }

    @Test
    fun `fresh upgrade with no bindings behaves like unpinned`() {
        // Post-MIGRATION_2_3 the table exists but is empty: no backfill of
        // the old positional mapping, and the default is still first-row.
        val rows = listOf(row("a"), row("b"))
        val stored = emptyList<WidgetBindingEntity>()
        assertTrue(WidgetBindingResolver.staleBindings(setOf("k1"), stored).isEmpty())
        assertEquals("a", WidgetBindingResolver.resolve(rows, null)!!.id)
        assertNull(WidgetBindingResolver.staleHint(rows, null))
    }

    @Test
    fun `empty type selects nothing whatever the binding`() {
        assertNull(WidgetBindingResolver.resolve(emptyList(), null))
        assertNull(WidgetBindingResolver.resolve(emptyList(), "a"))
        assertNull(WidgetBindingResolver.staleHint(emptyList(), null))
        // A pin with no rows at all still names the gone row for re-pointing.
        assertEquals("was-a", WidgetBindingResolver.staleHint(emptyList(), "a"))
    }

    @Test
    fun `no hint when the pinned row exists`() {
        val rows = listOf(row("a"), row("b"))
        assertNull(WidgetBindingResolver.staleHint(rows, "b"))
    }

    @Test
    fun `duplicates allowed two instances share one row`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals("a", WidgetBindingResolver.resolve(rows, "a")!!.id)
        assertEquals("a", WidgetBindingResolver.resolve(rows, "a")!!.id)
        assertNull(WidgetBindingResolver.staleHint(rows, "a"))
    }

    @Test
    fun `prune keeps bound keys and drops removed instances`() {
        val stored = listOf(binding("k1", "a"), binding("k2", null), binding("k3", "b"))
        val stale = WidgetBindingResolver.staleBindings(setOf("k1", "k2"), stored)
        assertEquals(listOf("k3"), stale.map { it.glanceKey })
    }

    @Test
    fun `prune with no bound instances drops everything`() {
        val stored = listOf(binding("k1", "a"))
        assertEquals(listOf("k1"), WidgetBindingResolver.staleBindings(emptySet(), stored).map { it.glanceKey })
    }

    @Test
    fun `prune with empty table drops nothing`() {
        assertTrue(
            WidgetBindingResolver.staleBindings(
                setOf("k1"),
                emptyList(),
            ).isEmpty(),
        )
    }
}
