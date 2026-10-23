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
 * Config-flow outcomes: saving resolves the checked option and pins +
 * pushes it; cancelling stores nothing and requests no push, so the
 * widget stays explicitly unassociated.
 */
class WidgetConfigActionsTest {

    private class FakeDao : WidgetBindingDao {
        val rows = mutableMapOf<Pair<String, String>, WidgetBindingEntity>()

        override suspend fun get(provider: String, key: String): WidgetBindingEntity? =
            rows[provider to key]

        override suspend fun getForProvider(provider: String): List<WidgetBindingEntity> =
            rows.values.filter { it.providerClass == provider }

        override suspend fun upsert(binding: WidgetBindingEntity) {
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
            rows = listOf(row("a"), row("b")),
            position = 2,
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
            rows = listOf(row("a"), row("b")),
            position = 0,
        )

        assertEquals(WidgetConfigActions.Outcome.Saved(null), outcome)
        assertNull(dao.get(entry.providerClass, glanceKey)!!.widgetId)
        assertEquals(1, pusher.pushes.size)
    }
}
