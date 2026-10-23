package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNamingTest {

    @Test
    fun `kind names and descriptions are distinct`() {
        assertEquals("Notes", NotificationKinds.displayName(NotificationKinds.NOTE))
        assertEquals("Important alerts", NotificationKinds.displayName(NotificationKinds.ALERT))
        assertNotEquals(
            NotificationKinds.description(NotificationKinds.NOTE),
            NotificationKinds.description(NotificationKinds.ALERT),
        )
    }

    @Test
    fun `explicit channel names after its id`() {
        val p = NotificationPayload.fromMap(mapOf("kind" to "alert", "channel" to "ops-alerts_2"))
        assertEquals("ops-alerts_2", p.channelId())
        assertEquals("Ops alerts 2", p.channelName())
        assertTrue(p.channelDescription().contains("ops-alerts_2"))
    }

    @Test
    fun `kind channels keep kind names`() {
        assertEquals("Notes", NotificationPayload.fromMap(mapOf("kind" to "note")).channelName())
        assertEquals(
            "Important alerts",
            NotificationPayload.fromMap(mapOf("kind" to "alert")).channelName(),
        )
    }

    @Test
    fun `unknown kinds keep the general name`() {
        val p = NotificationPayload.fromMap(mapOf("kind" to "future-kind"))
        assertEquals("General notifications", p.channelName())
        assertEquals(NotificationPayload.GENERAL_CHANNEL_ID, p.channelId())
    }

    @Test
    fun `invalid explicit channels fall back to kind naming`() {
        val p = NotificationPayload.fromMap(mapOf("kind" to "alert", "channel" to "has space"))
        assertEquals("pc_alert", p.channelId())
        assertEquals("Important alerts", p.channelName())
    }

    @Test
    fun `channel id humanization handles separators`() {
        assertEquals("Ops", NotificationKinds.humanizeChannelId("ops"))
        assertEquals("Night shift", NotificationKinds.humanizeChannelId("night-shift"))
        assertEquals("Ops alerts 2", NotificationKinds.humanizeChannelId("OPS-ALERTS_2".lowercase()))
    }
}
