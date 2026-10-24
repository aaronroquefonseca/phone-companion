package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetBindingDao
import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.widgets.WidgetDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config-flow outcomes: saving resolves the pending pick to a stable row
 * and pins + pushes it; a cache shift between render and save never
 * slides the pin onto a neighbor; a vanished pick fails closed to null;
 * cancelling stores nothing and requests no push; saving without loaded
 * options writes nothing; a pin failure surfaces instead of reporting
 * success.
 */
class WidgetConfigActionsTest {

    private class FakeDao(var failWrites: Boolean = false) : WidgetBindingDao {
        val rows = mutableMapOf<Pair<String, String>, WidgetBindingEntity>()

        override suspend fun get(provider: String, key: String): WidgetBindingEntity? =
            rows[provider to key]

        override suspend fun getForProvider(provider: String): List<WidgetBindingEntity> =
            rows.values.filter { it.providerClass == provider }

        override suspend fun upsert(binding: WidgetBindingEntity) {
            if (failWrites) throw RuntimeException("db down")
            rows[binding.providerClass to binding.glanceKey] = binding
        }

        override suspend fun delete(provider: String, key: String) {
            rows.remove(provider to key)
        }
    }

    private class FakePusher : WidgetBinder.Pusher {
        val pushes = mutableListOf<Pair<String, String>>()

        override suspend fun push(providerClass: String, glanceKey: String) {
            pushes += providerClass to glanceKey
        }
    }

    private fun run(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    private fun row(id: String) = WidgetCacheEntity(
        id = id,
        type = "text",
        title = "t-$id",
        updatedAt = "2026-01-10T10:00:00Z",
        payloadJson = """{"body":"$id"}""",
    )

    private val entry = WidgetDirectory.forType("text")!!
    private val glanceKey = "AppWidgetId(appWidgetId=51)"

    @Test
    fun `cancel stores nothing and requests no push`() = run {
        val dao = FakeDao()
        dao.rows["p" to "k"] = WidgetBindingEntity("p", "k", "text", "old")
        val pusher = FakePusher()

        val outcome = WidgetConfigActions.cancel()

        assertEquals(WidgetConfigActions.Outcome.Cancelled, outcome)
        assertEquals(mapOf("p" to "k" to WidgetBindingEntity("p", "k", "text", "old")), dao.rows)
        assertTrue(pusher.pushes.isEmpty())
    }

    @Test
    fun `save pins the chosen row and pushes the instance`() = run {
        val dao = FakeDao()
        val pusher = FakePusher()

        val outcome = WidgetConfigActions.save(
            dao = dao,
            pusher = pusher,
            entry = entry,
            glanceKey = glanceKey,
            freshRows = listOf(row("a"), row("b")),
            pending = WidgetChooser.PendingPick.Chosen("b"),
            fallbackPosition = 0,
            snapshotRows = listOf(row("a"), row("b")),
            optionsLoaded = true,
        )

        assertEquals(WidgetConfigActions.Outcome.Saved("b"), outcome)
        assertEquals("b", dao.get(entry.providerClass, glanceKey)!!.widgetId)
        assertEquals(listOf(entry.providerClass to glanceKey), pusher.pushes)
    }

    @Test
    fun `save on not-associated stores null and still pushes`() = run {
        val dao = FakeDao()
        val pusher = FakePusher()

        val outcome = WidgetConfigActions.save(
            dao = dao,
            pusher = pusher,
            entry = entry,
            glanceKey = glanceKey,
            freshRows = listOf(row("a"), row("b")),
            pending = WidgetChooser.PendingPick.Chosen(null),
            fallbackPosition = 0,
            snapshotRows = listOf(row("a"), row("b")),
            optionsLoaded = true,
        )

        assertEquals(WidgetConfigActions.Outcome.Saved(null), outcome)
        assertNull(dao.get(entry.providerClass, glanceKey)!!.widgetId)
        assertEquals(1, pusher.pushes.size)
    }

    @Test
    fun `save after a cache shift pins the tapped id not the neighbor`() = run {
        // Render showed [a, b, c]; the user tapped b (option position 2).
        // The cache shifted to [x, y, b] before Save: position mapping
        // against the fresh rows would slide the pin onto y.
        val dao = FakeDao()
        val pusher = FakePusher()
        val snapshot = listOf(row("a"), row("b"), row("c"))
        val fresh = listOf(row("x"), row("y"), row("b"))

        val outcome = WidgetConfigActions.save(
            dao = dao,
            pusher = pusher,
            entry = entry,
            glanceKey = glanceKey,
            freshRows = fresh,
            pending = WidgetChooser.PendingPick.Chosen("b"),
            fallbackPosition = 2,
            snapshotRows = snapshot,
            optionsLoaded = true,
        )

        assertEquals(WidgetConfigActions.Outcome.Saved("b"), outcome)
        assertEquals("b", dao.get(entry.providerClass, glanceKey)!!.widgetId)
        assertEquals(1, pusher.pushes.size)
    }

    @Test
    fun `save with a vanished pick fails closed to null`() = run {
        // The tapped row is gone from the fresh cache: pin null (never a
        // neighbor), still push so the widget renders unassociated.
        val dao = FakeDao()
        val pusher = FakePusher()

        val outcome = WidgetConfigActions.save(
            dao = dao,
            pusher = pusher,
            entry = entry,
            glanceKey = glanceKey,
            freshRows = listOf(row("x"), row("y")),
            pending = WidgetChooser.PendingPick.Chosen("b"),
            fallbackPosition = 2,
            snapshotRows = listOf(row("a"), row("b"), row("c")),
            optionsLoaded = true,
        )

        assertEquals(WidgetConfigActions.Outcome.Saved(null), outcome)
        assertNull(dao.get(entry.providerClass, glanceKey)!!.widgetId)
        assertEquals(1, pusher.pushes.size)
    }

    @Test
    fun `untouched chooser falls back to the render-time snapshot`() = run {
        // No user tap: the checked position indexes the rows shown at
        // render time, not the shifted fresh cache.
        val dao = FakeDao()
        val pusher = FakePusher()

        val outcome = WidgetConfigActions.save(
            dao = dao,
            pusher = pusher,
            entry = entry,
            glanceKey = glanceKey,
            freshRows = listOf(row("x"), row("y"), row("z")),
            pending = WidgetChooser.PendingPick.None,
            fallbackPosition = 2,
            snapshotRows = listOf(row("a"), row("b"), row("c")),
            optionsLoaded = true,
        )

        assertEquals(WidgetConfigActions.Outcome.Saved("b"), outcome)
        assertEquals("b", dao.get(entry.providerClass, glanceKey)!!.widgetId)
        assertEquals(1, pusher.pushes.size)
    }

    @Test
    fun `save without loaded options writes nothing and pushes nothing`() = run {
        // Failed options render (or Save raced ahead of it): the call is
        // unavailable even with a pending pick and a checked position that
        // would otherwise wipe the pin via the no-check null path.
        val dao = FakeDao()
        dao.rows[entry.providerClass to glanceKey] =
            WidgetBindingEntity(entry.providerClass, glanceKey, "text", "keep")
        val pusher = FakePusher()

        val outcome = WidgetConfigActions.save(
            dao = dao,
            pusher = pusher,
            entry = entry,
            glanceKey = glanceKey,
            freshRows = listOf(row("a")),
            pending = WidgetChooser.PendingPick.Chosen("a"),
            fallbackPosition = 0,
            snapshotRows = emptyList(),
            optionsLoaded = false,
        )

        assertEquals(WidgetConfigActions.Outcome.Unavailable, outcome)
        assertEquals("keep", dao.get(entry.providerClass, glanceKey)!!.widgetId)
        assertTrue(pusher.pushes.isEmpty())
    }

    @Test
    fun `save surfaces a pin failure without pushing`() = run {
        val dao = FakeDao(failWrites = true)
        val pusher = FakePusher()
        try {
            WidgetConfigActions.save(
                dao = dao,
                pusher = pusher,
                entry = entry,
                glanceKey = glanceKey,
                freshRows = listOf(row("a")),
                pending = WidgetChooser.PendingPick.Chosen("a"),
                fallbackPosition = 1,
                snapshotRows = listOf(row("a")),
                optionsLoaded = true,
            )
            throw AssertionError("pin failure must surface, not report success")
        } catch (e: RuntimeException) {
            assertEquals("db down", e.message)
        }
        assertTrue(pusher.pushes.isEmpty())
    }
}
