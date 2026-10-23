package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPayloadTest {

    @Test
    fun `empty data falls back to defaults`() {
        val p = NotificationPayload.fromMap(emptyMap())
        assertEquals("note", p.kind)
        assertEquals("default", p.priority)
        assertEquals(NotificationKinds.PAYLOAD_VERSION, p.version)
        assertTrue(p.actions.isEmpty())
        assertNull(p.imageUrl)
        assertNull(p.notificationId)
        assertTrue(p.isKnownKind)
    }

    @Test
    fun `unknown kind renders generically`() {
        val p = NotificationPayload.fromMap(mapOf("kind" to "future-kind", "v" to "99"))
        assertFalse(p.isKnownKind)
        assertEquals("pc_future-kind", p.channelId())
        assertEquals("General notifications", p.channelName())
        // Unknown payload version still renders rather than dropping.
        assertEquals("99", p.version)
    }

    @Test
    fun `explicit channel wins and is sanitized`() {
        val p = NotificationPayload.fromMap(mapOf("kind" to "alert", "channel" to "Ops Alerts!"))
        assertEquals("ops_alerts_", p.channelId())
    }

    @Test
    fun `kind channel defaults per kind`() {
        assertEquals("pc_note", NotificationPayload.fromMap(mapOf("kind" to "note")).channelId())
        assertEquals("pc_alert", NotificationPayload.fromMap(mapOf("kind" to "alert")).channelId())
    }

    @Test
    fun `actions parse flat keys and cap at three`() {
        val data = mapOf(
            "action_0_id" to "ack", "action_0_title" to "ACK",
            "action_1_id" to "snooze", "action_1_title" to "Snooze",
            "action_2_id" to " ", "action_2_title" to "Blank id skipped",
        )
        val p = NotificationPayload.fromMap(data)
        assertEquals(
            listOf(NotificationAction("ack", "ACK"), NotificationAction("snooze", "Snooze")),
            p.actions,
        )
    }

    @Test
    fun `https image accepted, others fail closed`() {
        assertEquals(
            "https://example.com/dinner.jpg",
            NotificationPayload.fromMap(mapOf("image_url" to "https://example.com/dinner.jpg")).imageUrl,
        )
        for (bad in listOf(
            "http://example.com/x.jpg",
            "https://",
            "https:///path-only",
            "",
            "not a url",
            "https://" + "x".repeat(2048),
        )) {
            assertNull(bad, NotificationPayload.fromMap(mapOf("image_url" to bad)).imageUrl)
        }
    }

    @Test
    fun `notify id is stable and prefers notification id`() {
        val a = NotificationPayload.fromMap(mapOf("notification_id" to "nid-1", "title" to "t"))
        val b = NotificationPayload.fromMap(mapOf("notification_id" to "nid-1", "title" to "other"))
        assertEquals(a.notifyId(), b.notifyId())
    }
}
