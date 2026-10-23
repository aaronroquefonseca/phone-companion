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
    fun `instance line shows default pinned stale and empty states`() {
        val rows = listOf(
            payloadEntity("text", """{"body":"hi"}""", id = "a").copy(title = "Hello"),
            payloadEntity("text", """{"body":"yo"}""", id = "b").copy(title = "World"),
        )
        val default = WidgetListUi.instanceLine("k1", rows, null, null)
        assertTrue(default.contains("k1"))
        assertTrue(default.contains("Default"))
        assertTrue(default.contains("Hello"))

        val pinned = WidgetListUi.instanceLine("k1", rows, "b", null)
        assertTrue(pinned.contains("Pinned"))
        assertTrue(pinned.contains("World"))

        val stale = WidgetListUi.instanceLine("k1", rows, "gone", "was-gone")
        assertTrue(stale.contains("was-gone"))
        assertTrue(stale.contains("Hello"))

        val empty = WidgetListUi.instanceLine("k1", emptyList(), null, null)
        assertTrue(empty.contains("no cached rows"))
    }
}
