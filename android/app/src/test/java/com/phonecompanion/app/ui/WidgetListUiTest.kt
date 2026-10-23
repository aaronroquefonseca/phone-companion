package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.work.PollResult
import com.phonecompanion.app.work.PollStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the list-screen contract: every cached row shows
 * id/type/title/updated_at, the status line always surfaces the last poll
 * outcome, and an empty cache names the outcome instead of staying silent.
 */
class WidgetListUiTest {

    private fun entity(
        id: String = "w1",
        type: String = "text",
        title: String = "Hello",
        updatedAt: String = "2026-01-01T00:00:00Z",
    ) = WidgetCacheEntity(
        id = id,
        type = type,
        title = title,
        updatedAt = updatedAt,
        payloadJson = """{"body":"hi"}""",
    )

    private fun authStatus() = PollStatus(
        result = PollResult.AUTH_FAILED,
        httpCode = 401,
        timestampMs = 1_700_000_000_000L,
        consecutiveAuthFailures = 1,
    )

    private companion object {
        const val ISO_NOW = "2026-01-10T12:00:00Z"
        const val ISO_NOW_MS = 1_768_046_400_000L // ISO_NOW as epoch millis.

        fun minusMinutes(minutes: Long): String {
            val then = java.time.Instant.parse(ISO_NOW).minusSeconds(minutes * 60)
            return then.toString()
        }
    }

    @Test
    fun `row shows id type title and updated_at`() {
        val row = WidgetListUi.rowText("w1", "secret", "Keys", "2026-02-02T00:00:00Z")
        assertTrue(row.contains("w1"))
        assertTrue(row.contains("secret"))
        assertTrue(row.contains("Keys"))
        assertTrue(row.contains("2026-02-02T00:00:00Z"))
    }

    @Test
    fun `row texts render every cached widget in order`() {
        val rows = WidgetListUi.rowTexts(listOf(entity("a"), entity("b", type = "list")))
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("a"))
        assertTrue(rows[1].contains("b") && rows[1].contains("list"))
    }

    @Test
    fun `status text surfaces the last poll outcome`() {
        val text = WidgetListUi.statusText(authStatus())
        assertTrue(text.contains("auth-failed-attempt-1-of-2"))
        assertTrue(text.contains("401"))
    }

    @Test
    fun `network error status surfaces code`() {
        val text = WidgetListUi.statusText(
            PollStatus(PollResult.NON_AUTH_ERROR, 500, 1_700_000_000_000L, 0),
        )
        assertTrue(text.contains("network-error"))
        assertTrue(text.contains("500"))
    }

    @Test
    fun `missing status renders never-polled marker`() {
        assertTrue(WidgetListUi.statusText(null).contains("never-polled"))
    }

    @Test
    fun `empty cache names the last outcome instead of staying silent`() {
        assertTrue(WidgetListUi.emptyStateText(authStatus()).contains("No cached widgets"))
        assertTrue(WidgetListUi.emptyStateText(authStatus()).contains("auth-failed-attempt-1-of-2"))
        assertTrue(WidgetListUi.emptyStateText(null).contains("never-polled"))
    }

    private fun payloadEntity(
        type: String,
        payloadJson: String,
        id: String = "w1",
    ) = WidgetCacheEntity(
        id = id,
        type = type,
        title = "T",
        updatedAt = "2026-01-01T00:00:00Z",
        payloadJson = payloadJson,
    )

    @Test
    fun `healthy rows of every type read ok`() {
        assertEquals(
            WidgetListUi.HEALTH_OK,
            WidgetListUi.payloadHealth(payloadEntity("text", """{"body":"hi"}""")),
        )
        assertEquals(
            WidgetListUi.HEALTH_OK,
            WidgetListUi.payloadHealth(payloadEntity("list", """{"items":[]}""")),
        )
        assertEquals(
            WidgetListUi.HEALTH_OK,
            WidgetListUi.payloadHealth(payloadEntity("status", """{"state":"ok","detail":"d"}""")),
        )
        assertEquals(
            WidgetListUi.HEALTH_ENCRYPTED_SHAPE,
            WidgetListUi.payloadHealth(
                payloadEntity(
                    "secret",
                    """{"alg":"AES-256-GCM","kid":"k","nonce":"n","ciphertext":"c"}""",
                ),
            ),
        )
    }

    @Test
    fun `unparseable payloads read unparseable, unknown types read unknown`() {
        assertEquals(
            WidgetListUi.HEALTH_UNPARSEABLE,
            WidgetListUi.payloadHealth(payloadEntity("text", "{}")),
        )
        assertEquals(
            WidgetListUi.HEALTH_UNPARSEABLE,
            WidgetListUi.payloadHealth(payloadEntity("status", "{}")),
        )
        assertEquals(
            WidgetListUi.HEALTH_UNPARSEABLE,
            WidgetListUi.payloadHealth(payloadEntity("secret", "{}")),
        )
        assertEquals(
            WidgetListUi.HEALTH_UNKNOWN_TYPE,
            WidgetListUi.payloadHealth(payloadEntity("teleport", """{"beam":"up"}""")),
        )
    }

    @Test
    fun `row texts carry the health marker`() {
        val rows = WidgetListUi.rowTexts(
            listOf(
                payloadEntity("text", """{"body":"hi"}""", id = "a"),
                payloadEntity("teleport", """{"beam":"up"}""", id = "b"),
            ),
        )
        assertTrue(rows[0].contains(WidgetListUi.HEALTH_OK))
        assertTrue(rows[1].contains(WidgetListUi.HEALTH_UNKNOWN_TYPE))
    }

    @Test
    fun `instance label shows human selection never raw glance ids`() {
        val rows = listOf(
            payloadEntity("text", """{"body":"hi"}""", id = "a").copy(title = "Hello"),
            payloadEntity("text", """{"body":"yo"}""", id = "b").copy(title = "World"),
        )
        val unbound = WidgetListUi.instanceLabel("Text", rows, null, null, ISO_NOW_MS)
        assertTrue(unbound, unbound.contains("Not associated"))
        assertTrue(unbound, unbound.contains("Text"))
        assertTrue(unbound, !unbound.contains("APPWIDGETID") && !unbound.contains("AppWidgetId"))

        val pinned = WidgetListUi.instanceLabel("Text", rows, "b", null, ISO_NOW_MS)
        assertTrue(pinned, pinned.contains("World"))
        assertTrue(pinned, pinned.contains("Text"))
        assertTrue(pinned, !pinned.contains("Pinned") && !pinned.contains("Default"))

        val stale = WidgetListUi.instanceLabel("Text", rows, "gone", "was-gone", ISO_NOW_MS)
        assertTrue(stale, stale.contains("Not associated"))
        assertTrue(stale, stale.contains("was-gone"))

        val empty = WidgetListUi.instanceLabel("Text", emptyList(), null, null, ISO_NOW_MS)
        assertTrue(empty, empty.contains("Text"))
        assertTrue(empty, empty.contains("No cached rows"))
    }

    @Test
    fun `human updated-at reads in words and never throws`() {
        assertEquals("just now", WidgetListUi.humanUpdatedAt(ISO_NOW, ISO_NOW_MS))
        assertEquals("1 minute ago", WidgetListUi.humanUpdatedAt(minusMinutes(1), ISO_NOW_MS))
        assertEquals("5 minutes ago", WidgetListUi.humanUpdatedAt(minusMinutes(5), ISO_NOW_MS))
        assertEquals("1 hour ago", WidgetListUi.humanUpdatedAt(minusMinutes(60), ISO_NOW_MS))
        assertEquals("3 hours ago", WidgetListUi.humanUpdatedAt(minusMinutes(180), ISO_NOW_MS))
        assertEquals("1 day ago", WidgetListUi.humanUpdatedAt(minusMinutes(24 * 60), ISO_NOW_MS))
        assertEquals("6 days ago", WidgetListUi.humanUpdatedAt(minusMinutes(6 * 24 * 60), ISO_NOW_MS))
        assertEquals("2025-12-01", WidgetListUi.humanUpdatedAt("2025-12-01T00:00:00Z", ISO_NOW_MS))
        // Unparseable and future inputs pass through unchanged.
        assertEquals("not-a-date", WidgetListUi.humanUpdatedAt("not-a-date", ISO_NOW_MS))
        assertEquals("", WidgetListUi.humanUpdatedAt("", ISO_NOW_MS))
        assertEquals(
            "2026-02-01T00:00:00Z",
            WidgetListUi.humanUpdatedAt("2026-02-01T00:00:00Z", ISO_NOW_MS),
        )
    }
}
