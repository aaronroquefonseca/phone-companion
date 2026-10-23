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
        assertFalse(p.isSecret)
    }

    @Test
    fun `unknown kind renders generically on the shared channel`() {
        val p = NotificationPayload.fromMap(mapOf("kind" to "future-kind", "v" to "99"))
        assertFalse(p.isKnownKind)
        // Channel allowlist: unknown kinds share pc_general (older builds
        // minted pc_future-kind — see legacyChannelIdForCleanup).
        assertEquals(NotificationPayload.GENERAL_CHANNEL_ID, p.channelId())
        assertEquals("General notifications", p.channelName())
        // Unknown payload version still renders rather than dropping.
        assertEquals("99", p.version)
    }

    @Test
    fun `legacy per-kind channel is cleaned up for unknown kinds only`() {
        val unknown = NotificationPayload.fromMap(mapOf("kind" to "future-kind"))
        assertEquals("pc_future-kind", unknown.legacyChannelIdForCleanup())
        // Known kinds have no legacy channel.
        assertNull(NotificationPayload.fromMap(mapOf("kind" to "note")).legacyChannelIdForCleanup())
        assertNull(NotificationPayload.fromMap(mapOf("kind" to "alert")).legacyChannelIdForCleanup())
        // A valid explicit channel suppresses cleanup (never delete it).
        val explicit = NotificationPayload.fromMap(
            mapOf("kind" to "future-kind", "channel" to "ops"),
        )
        assertNull(explicit.legacyChannelIdForCleanup())
        assertEquals("ops", explicit.channelId())
    }

    @Test
    fun `explicit channel wins and is validated`() {
        val p = NotificationPayload.fromMap(mapOf("kind" to "alert", "channel" to "Ops-Alerts_2"))
        assertEquals("ops-alerts_2", p.channelId())
        // Invalid explicit channels fail closed to the kind channel.
        for (bad in listOf("has space", "semi;colon", "quot\"e", ".leading", "-ok-but", "")) {
            val q = NotificationPayload.fromMap(mapOf("kind" to "alert", "channel" to bad))
            assertEquals(bad, "pc_alert", q.channelId())
        }
    }

    @Test
    fun `kind channel defaults per known kind`() {
        assertEquals("pc_note", NotificationPayload.fromMap(mapOf("kind" to "note")).channelId())
        assertEquals("pc_alert", NotificationPayload.fromMap(mapOf("kind" to "alert")).channelId())
    }

    @Test
    fun `parsed strings are length-bounded`() {
        val p = NotificationPayload.fromMap(
            mapOf(
                "kind" to "x".repeat(5000),
                "title" to "t".repeat(5000),
                "body" to "b".repeat(20000),
                "notification_id" to "n".repeat(5000),
                "title_env" to "e".repeat(100000),
                "action_0_id" to "i".repeat(5000),
                "action_0_title" to "a".repeat(5000),
            ),
        )
        assertEquals(NotificationPayload.MAX_KIND_CHARS, p.kind.length)
        assertEquals(NotificationPayload.MAX_TITLE_CHARS, p.title.length)
        assertEquals(NotificationPayload.MAX_BODY_CHARS, p.body.length)
        assertEquals(NotificationPayload.MAX_NOTIFICATION_ID_CHARS, p.notificationId!!.length)
        assertEquals(NotificationPayload.MAX_ENVELOPE_CHARS, p.titleEnvelope!!.length)
        assertEquals(1, p.actions.size)
        assertEquals(NotificationPayload.MAX_ACTION_ID_CHARS, p.actions[0].id.length)
        assertEquals(NotificationPayload.MAX_ACTION_TITLE_CHARS, p.actions[0].title.length)
    }

    @Test
    fun `secret payload parses envelopes without plaintext`() {
        val p = NotificationPayload.fromMap(
            mapOf(
                "secret" to "true",
                "notification_id" to "nid-1",
                "title_env" to "{\"alg\":\"AES-256-GCM\"}",
                "body_env" to "{\"alg\":\"AES-256-GCM\"}",
            ),
        )
        assertTrue(p.isSecret)
        assertEquals("nid-1", p.notificationId)
        assertEquals("{\"alg\":\"AES-256-GCM\"}", p.titleEnvelope)
        assertEquals("{\"alg\":\"AES-256-GCM\"}", p.bodyEnvelope)
        assertTrue(p.title.isEmpty())
        assertTrue(p.body.isEmpty())
    }

    @Test
    fun `blank notification falls back to app-name title and null body`() {
        // Empty data, blank strings, and undecryptable secrets (no
        // plaintext keys) all render the same fallback — no oracle.
        for (data in listOf(
            emptyMap(),
            mapOf("title" to "  ", "body" to ""),
            mapOf("secret" to "true", "notification_id" to "nid-x"),
        )) {
            val p = NotificationPayload.fromMap(data)
            assertEquals("My App", p.displayTitle("My App"))
            assertNull(p.displayBody())
        }
        // Non-blank content passes through untouched.
        val full = NotificationPayload.fromMap(mapOf("title" to "Hi", "body" to "Yo"))
        assertEquals("Hi", full.displayTitle("My App"))
        assertEquals("Yo", full.displayBody())
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

    @Test
    fun `image decode bounds math downsamples to 1024px`() {
        assertEquals(1, NotificationImages.sampleSizeFor(800, 600))
        assertEquals(1, NotificationImages.sampleSizeFor(1024, 1024))
        assertEquals(2, NotificationImages.sampleSizeFor(1025, 10))
        assertEquals(4, NotificationImages.sampleSizeFor(4000, 3000))
        // Garbage bytes fail closed (null) even on a bare JVM runner.
        assertNull(NotificationImages.decodeBounded(ByteArray(0)))
        assertNull(NotificationImages.decodeBounded("not an image".toByteArray()))
        assertNull(NotificationImages.download("http://example.com/x.jpg"))
    }
}
