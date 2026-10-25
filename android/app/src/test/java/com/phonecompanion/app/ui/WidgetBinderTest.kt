package com.phonecompanion.app.ui

import androidx.glance.GlanceId
import com.phonecompanion.app.data.WidgetBindingDao
import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.widgets.WidgetProviderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the configure-flow persistence contract: saving a choice stores the
 * binding (provider + Glance key + type + picked row, or null for
 * explicitly unassociated) and requests exactly one push for that
 * instance. A failed push keeps the pin; a failed pin skips the push.
 */
class WidgetBinderTest {

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

    private class FakePusher(var fail: Boolean = false) : WidgetBinder.Pusher {
        val pushes = mutableListOf<Pair<String, String>>()

        override suspend fun push(providerClass: String, glanceKey: String) {
            if (fail) throw RuntimeException("push down")
            pushes += providerClass to glanceKey
        }
    }

    private fun run(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    @Test
    fun `saving a pick stores the binding and pushes the instance`() = run {
        val dao = FakeDao()
        val pusher = FakePusher()
        WidgetBinder.pinAndPush(
            dao = dao,
            pusher = pusher,
            providerClass = "com.phonecompanion.app.widgets.CompanionWidget",
            widgetType = "text",
            glanceKey = "AppWidgetId(appWidgetId=51)",
            widgetId = "hello",
        )
        val stored = dao.get(
            "com.phonecompanion.app.widgets.CompanionWidget",
            "AppWidgetId(appWidgetId=51)",
        )!!
        assertEquals("hello", stored.widgetId)
        assertEquals("text", stored.widgetType)
        assertEquals(
            listOf(
                "com.phonecompanion.app.widgets.CompanionWidget" to "AppWidgetId(appWidgetId=51)",
            ),
            pusher.pushes,
        )
    }

    @Test
    fun `clearing the pick stores null and still pushes`() = run {
        val dao = FakeDao()
        val pusher = FakePusher()
        WidgetBinder.pinAndPush(
            dao = dao,
            pusher = pusher,
            providerClass = "com.phonecompanion.app.widgets.CompanionWidget",
            widgetType = "text",
            glanceKey = "AppWidgetId(appWidgetId=51)",
            widgetId = null,
        )
        assertNull(dao.get("com.phonecompanion.app.widgets.CompanionWidget", "AppWidgetId(appWidgetId=51)")!!.widgetId)
        assertEquals(1, pusher.pushes.size)
    }

    @Test
    fun `failed push keeps the pin`() = run {
        val dao = FakeDao()
        val pusher = FakePusher(fail = true)
        WidgetBinder.pinAndPush(
            dao = dao,
            pusher = pusher,
            providerClass = "p",
            widgetType = "text",
            glanceKey = "k",
            widgetId = "hello",
        )
        assertEquals("hello", dao.get("p", "k")!!.widgetId)
        assertTrue(pusher.pushes.isEmpty())
    }

    @Test
    fun `failed pin propagates and skips the push`() = run {
        val dao = FakeDao(failWrites = true)
        val pusher = FakePusher()
        try {
            WidgetBinder.pinAndPush(
                dao = dao,
                pusher = pusher,
                providerClass = "p",
                widgetType = "text",
                glanceKey = "k",
                widgetId = "hello",
            )
            throw AssertionError("pin failure must propagate, not report success")
        } catch (e: RuntimeException) {
            assertEquals("db down", e.message)
        }
        assertTrue(pusher.pushes.isEmpty())
    }

    @Test
    fun `re-pointing overwrites the previous binding`() = run {
        val dao = FakeDao()
        val pusher = FakePusher()
        val args = arrayOf(
            "com.phonecompanion.app.widgets.CompanionWidget", "secret", "k1",
        )
        WidgetBinder.pinAndPush(dao, pusher, args[0], args[1], args[2], "a")
        WidgetBinder.pinAndPush(dao, pusher, args[0], args[1], args[2], "b")
        assertEquals("b", dao.get(args[0], args[2])!!.widgetId)
        assertEquals(2, pusher.pushes.size)
    }

    private fun fakeGlanceId(key: String): GlanceId = object : GlanceId {
        override fun toString(): String = key
    }

    private fun realPusher(
        glanceIdsFor: suspend (Class<out androidx.glance.appwidget.GlanceAppWidget>) -> List<GlanceId>,
        onUpdate: (WidgetProviderEntry, GlanceId) -> Unit = { _, _ -> },
        logged: MutableList<String> = mutableListOf(),
    ): WidgetBinder.GlancePusher = WidgetBinder.GlancePusher(
        glanceIdsFor = glanceIdsFor,
        pushUpdate = { entry, id -> onUpdate(entry, id) },
        log = { logged += it },
    )

    @Test
    fun `real pusher ignores unknown providers without touching glance`() = run {
        var lookups = 0
        var updates = 0
        realPusher(
            glanceIdsFor = {
                lookups++
                emptyList()
            },
            onUpdate = { _, _ -> updates++ },
        ).push("com.example.Nope", "k")
        assertEquals(0, lookups)
        assertEquals(0, updates)
    }

    @Test
    fun `real pusher lookup failure is a silent no-op`() = run {
        var updates = 0
        val logged = mutableListOf<String>()
        realPusher(
            glanceIdsFor = { throw RuntimeException("glance down") },
            onUpdate = { _, _ -> updates++ },
            logged = logged,
        ).push("com.phonecompanion.app.widgets.CompanionWidget", "k")
        assertEquals(0, updates)
        // Shape only: the exception class, never the instance key.
        assertEquals(listOf("chooser push lookup-failed error=RuntimeException"), logged)
    }

    @Test
    fun `real pusher key miss updates nothing`() = run {
        var updates = 0
        realPusher(
            glanceIdsFor = { listOf(fakeGlanceId("AppWidgetId(appWidgetId=7)")) },
            onUpdate = { _, _ -> updates++ },
        ).push("com.phonecompanion.app.widgets.CompanionWidget", "AppWidgetId(appWidgetId=51)")
        assertEquals(0, updates)
    }

    @Test
    fun `real pusher update failure is a silent no-op`() = run {
        val logged = mutableListOf<String>()
        realPusher(
            glanceIdsFor = { listOf(fakeGlanceId("AppWidgetId(appWidgetId=51)")) },
            onUpdate = { _, _ -> throw RuntimeException("update down") },
            logged = logged,
        ).push("com.phonecompanion.app.widgets.CompanionWidget", "AppWidgetId(appWidgetId=51)")
        assertEquals(listOf("chooser push update-failed error=RuntimeException"), logged)
    }

    @Test
    fun `real pusher updates the matching bound instance`() = run {
        val id = fakeGlanceId("AppWidgetId(appWidgetId=51)")
        var updated: Pair<WidgetProviderEntry, GlanceId>? = null
        realPusher(
            glanceIdsFor = { listOf(fakeGlanceId("AppWidgetId(appWidgetId=7)"), id) },
            onUpdate = { entry, target -> updated = entry to target },
        ).push("com.phonecompanion.app.widgets.CompanionWidget", "AppWidgetId(appWidgetId=51)")
        val (entry, target) = updated ?: throw AssertionError("no update requested")
        assertEquals("all", entry.type)
        assertSame(id, target)
    }
}
