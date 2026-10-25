package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.data.WidgetCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-instance selection: resolve / unbound / stale / duplicate / upgrade.
 *
 * The contract: a pinned instance shows its row; every unassociated state
 * (null binding, stale pin, empty cache) selects nothing — the widget
 * renders its explicit "Not associated" state and never another row's
 * content. Stale pins keep a `was-<old id>` re-point hint; duplicates are
 * allowed; a fresh upgrade (no bindings yet) behaves like unassociated.
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
        provider: String = "com.phonecompanion.app.widgets.CompanionWidget",
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
    fun `no binding selects nothing instead of another rows content`() {
        val rows = listOf(row("a"), row("b"))
        assertNull(WidgetBindingResolver.resolve(rows, null))
        assertNull(WidgetBindingResolver.staleHint(rows, null))
    }

    @Test
    fun `stale miss selects nothing with a was-hint`() {
        val rows = listOf(row("a"), row("b"))
        assertNull(WidgetBindingResolver.resolve(rows, "gone"))
        assertEquals("was-gone", WidgetBindingResolver.staleHint(rows, "gone"))
    }

    @Test
    fun `fresh upgrade with no bindings selects nothing`() {
        // The table exists but is empty: no backfill, no silent default.
        val rows = listOf(row("a"), row("b"))
        val stored = emptyList<WidgetBindingEntity>()
        assertTrue(WidgetBindingResolver.staleBindings(setOf("k1"), stored).isEmpty())
        assertNull(WidgetBindingResolver.resolve(rows, null))
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

    @Test
    fun `glance key for app widget id matches the Glance data-class format`() {
        // Glance 1.1.1 AppWidgetId is a data class over appWidgetId, so
        // GlanceId.toString() is exactly this string (verified against the
        // shipped AAR). The placement config flow depends on it.
        assertEquals("AppWidgetId(appWidgetId=51)", glanceKeyForAppWidgetId(51))
        assertEquals("AppWidgetId(appWidgetId=1)", glanceKeyForAppWidgetId(1))
    }
}
