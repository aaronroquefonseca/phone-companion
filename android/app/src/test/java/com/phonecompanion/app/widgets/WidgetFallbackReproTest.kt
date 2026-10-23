package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetEnvelope
import com.phonecompanion.app.data.WidgetJson
import com.phonecompanion.app.data.WidgetPayloadParser
import com.phonecompanion.app.ui.WidgetListUi
import com.phonecompanion.app.work.WidgetPoller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * REPRO-FIRST for the all-fallback incident: backend serves valid 200
 * envelopes and the in-app list (`getAll()`) looks populated, yet every
 * home-screen Glance instance renders only its fallback.
 *
 * Each test below drives the real load chain (poll mapping -> cache row ->
 * per-type parse -> list-screen text) for one deterministic repro
 * candidate. A failing test IS the bug; the fix must make every fallback
 * path correspond to a genuinely-empty/missing/unpaired/decrypt-failed
 * state only.
 */
class WidgetFallbackReproTest {

    private fun cacheRow(
        id: String = "hello",
        type: String = "text",
        title: String = "Hello",
        payloadJson: String = """{"body":"hi"}""",
    ) = WidgetCacheEntity(
        id = id,
        type = type,
        title = title,
        updatedAt = "2026-10-23T00:00:00Z",
        payloadJson = payloadJson,
    )

    /**
     * The wire format allows a missing payload (`WidgetEnvelope.payload`
     * is nullable). Caching it as `"{}"` poisons the row: `getAll()` shows
     * a healthy-looking entry while every per-type parse yields null and
     * the widget falls back. The mapping refuses (fail fast) and the batch
     * split drops such envelopes so the last good row survives.
     */
    @Test
    fun `null payload is never cached as unparseable poison`() {
        try {
            WidgetPoller.toCacheEntity(
                WidgetEnvelope(
                    id = "hello",
                    type = "text",
                    title = "Hello",
                    updatedAt = "2026-10-23T00:00:00Z",
                    payload = null,
                ),
            )
            fail("null payload must refuse to serialize, not cache \"{}\"")
        } catch (e: IllegalArgumentException) {
            // Expected: fail fast instead of poisoning the cache.
        }
    }

    @Test
    fun `batch split drops null payloads and keeps valid siblings`() {
        val batch = WidgetPoller.toCacheEntities(
            listOf(
                WidgetEnvelope(
                    id = "hello",
                    type = "text",
                    title = "Hello",
                    updatedAt = "2026-10-23T00:00:00Z",
                    payload = mapOf("body" to "hi"),
                ),
                WidgetEnvelope(
                    id = "ghost",
                    type = "text",
                    title = "Ghost",
                    updatedAt = "2026-10-23T00:00:00Z",
                    payload = null,
                ),
            ),
        )
        assertEquals(1, batch.dropped)
        assertEquals(listOf("hello"), batch.kept.map { it.id })
        assertEquals("""{"body":"hi"}""", batch.kept.single().payloadJson)
    }

    /**
     * A `"{}"` text row carries no body, so the widget falls back — but the
     * list screen must say so (payload-shape health), otherwise operators
     * see a populated cache and misdiagnose a render/push failure.
     */
    @Test
    fun `empty-payload text row is flagged on the list screen`() {
        val line = WidgetListUi.rowTexts(
            listOf(cacheRow(payloadJson = "{}")),
        ).single()
        assertTrue("operator-blind poison row: $line", line.contains("unparseable"))
    }

    /**
     * Rows whose type matches none of the four literals are invisible to
     * every per-type Glance query while `getAll()` still lists them. The
     * list screen must flag them so the getAll/getByType disagreement is
     * visible instead of presenting as a render bug.
     */
    @Test
    fun `unknown-type rows are flagged on the list screen`() {
        val line = WidgetListUi.rowTexts(
            listOf(cacheRow(id = "mystery", type = "teleport", title = "Mystery")),
        ).single()
        assertTrue("operator-blind unknown type: $line", line.contains("unknown-type"))
    }

    /**
     * Nested payloads (list `meta` objects/arrays, status `extra`) must
     * survive the exact serialize -> parse pair the poll + render path
     * uses. If this fails, the cache writer — not the renderer — is the
     * all-fallback root cause.
     */
    @Test
    fun `nested list payload survives the cache round-trip`() {
        val nested = mapOf<String, Any?>(
            "items" to listOf(
                mapOf("id" to "a", "text" to "Buy milk", "done" to false),
                mapOf(
                    "id" to "b",
                    "text" to "Eggs",
                    "done" to true,
                    "meta" to mapOf("aisle" to 3, "tags" to listOf("dairy", "urgent")),
                ),
            ),
            "meta" to mapOf("owner" to "ops"),
        )
        val before = WidgetPayloadParser.parseListItems(WidgetJson.toJson(nested))
        val after = WidgetPayloadParser.parseListItems(
            WidgetJson.toJson(WidgetJson.fromJson(WidgetJson.toJson(nested))),
        )
        assertEquals(before, after)
    }

    /**
     * Populated + matching + well-formed cache never renders the fallback,
     * per type. Pins the fixed point: resolution finds the row and the
     * parse yields content. Unbound (null) and stale pins select nothing —
     * the model layer (see WidgetContentTest) renders "Not associated".
     */
    @Test
    fun `matching text row with valid payload yields content`() {
        val rows = listOf(cacheRow())
        val row = WidgetBindingResolver.resolve(rows, "hello")!!
        assertEquals("hi", WidgetPayloadParser.parseTextBody(row.payloadJson))
    }

    @Test
    fun `unbound and stale text pins select nothing`() {
        val rows = listOf(cacheRow())
        assertNull(WidgetBindingResolver.resolve(rows, null))
        assertNull(WidgetBindingResolver.resolve(rows, "ghost"))
        assertEquals("was-ghost", WidgetBindingResolver.staleHint(rows, "ghost"))
    }

    @Test
    fun `matching list rows with valid payload yield items`() {
        val rows = listOf(
            cacheRow(
                id = "tasks",
                type = "list",
                title = "Tasks",
                payloadJson = """{"items":[{"id":"a","text":"Milk","done":true}]}""",
            ),
        )
        val row = WidgetBindingResolver.resolve(rows, "tasks")!!
        assertEquals(
            listOf(WidgetPayloadParser.ListItem("Milk", true)),
            WidgetPayloadParser.parseListItems(row.payloadJson),
        )
    }

    @Test
    fun `matching status row with valid payload yields state`() {
        val rows = listOf(
            cacheRow(
                id = "state",
                type = "status",
                title = "Server",
                payloadJson = """{"state":"ok","detail":"All systems go"}""",
            ),
        )
        val row = WidgetBindingResolver.resolve(rows, "state")!!
        assertEquals(
            WidgetPayloadParser.Status("ok", "All systems go"),
            WidgetPayloadParser.parseStatus(row.payloadJson),
        )
    }
}
