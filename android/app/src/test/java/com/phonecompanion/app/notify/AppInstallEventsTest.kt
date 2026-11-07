package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Foreground-only in-app surfacing (one dialog per command id).
 *
 * Pins: observer presence is the foreground gate (an emit with no
 * observer delivers nowhere and is NOT queued for a later observer —
 * the notification covers background); the first emit for a command id
 * marks it so repeats are dropped (rotation/re-poll never duplicate);
 * within one emit the first accepting observer wins; a throwing or
 * declining observer never blocks the rest.
 */
class AppInstallEventsTest {

    private fun payload(commandId: String) = AppInstallPayload.fromMap(
        mapOf(
            "type" to "app_install",
            "command_id" to commandId,
            "push_id" to "push-$commandId",
            "apk_url" to "https://example.com/$commandId.apk",
            "sha256" to "c".repeat(64),
            "package" to "com.example.app",
            "label" to "App $commandId",
        ),
    )!!

    @Before
    fun clearHub() {
        AppInstallEvents.clearForTest()
    }

    @Test
    fun `emit with no observers delivers nowhere and is not queued`() {
        // Background/worker context: nobody foregrounded, so nothing may
        // happen — and a later foreground must NOT see it either (the
        // status-bar notification already covered it).
        assertFalse(AppInstallEvents.emit(payload("cmd-bg")))
        var seen = 0
        AppInstallEvents.register(AppInstallEvents.Observer { seen++; true })
        assertFalse(AppInstallEvents.emit(payload("cmd-bg")))
        assertEquals(0, seen)
    }

    @Test
    fun `one emit per command id even across re-registration`() {
        val seen = mutableListOf<String>()
        val observer = AppInstallEvents.Observer { p -> seen += p.commandId; true }
        AppInstallEvents.register(observer)
        assertTrue(AppInstallEvents.emit(payload("cmd-1")))
        // Rotation: re-register is a no-op for delivery — the repeat emit
        // is dropped, never a second dialog.
        AppInstallEvents.unregister(observer)
        AppInstallEvents.register(observer)
        assertFalse(AppInstallEvents.emit(payload("cmd-1")))
        assertEquals(listOf("cmd-1"), seen)
    }

    @Test
    fun `distinct command ids each deliver once`() {
        val seen = mutableListOf<String>()
        AppInstallEvents.register(AppInstallEvents.Observer { p -> seen += p.commandId; true })
        assertTrue(AppInstallEvents.emit(payload("cmd-a")))
        assertTrue(AppInstallEvents.emit(payload("cmd-b")))
        assertEquals(listOf("cmd-a", "cmd-b"), seen)
    }

    @Test
    fun `first accepting observer wins within one emit`() {
        val calls = mutableListOf<String>()
        AppInstallEvents.register(AppInstallEvents.Observer { p -> calls += "first:${p.commandId}"; false })
        AppInstallEvents.register(AppInstallEvents.Observer { p -> calls += "second:${p.commandId}"; true })
        AppInstallEvents.register(AppInstallEvents.Observer { p -> calls += "third:${p.commandId}"; true })
        assertTrue(AppInstallEvents.emit(payload("cmd-1")))
        // The decliner yields, the first accepter wins, the rest stay silent.
        assertEquals(listOf("first:cmd-1", "second:cmd-1"), calls)
    }

    @Test
    fun `throwing observer never blocks the rest`() {
        val seen = mutableListOf<String>()
        AppInstallEvents.register(
            AppInstallEvents.Observer { throw IllegalStateException("dead screen") },
        )
        AppInstallEvents.register(AppInstallEvents.Observer { p -> seen += p.commandId; true })
        assertTrue(AppInstallEvents.emit(payload("cmd-1")))
        assertEquals(listOf("cmd-1"), seen)
    }

    @Test
    fun `unregistered observer sees nothing further`() {
        val seen = mutableListOf<String>()
        val observer = AppInstallEvents.Observer { p -> seen += p.commandId; true }
        AppInstallEvents.register(observer)
        AppInstallEvents.unregister(observer)
        assertFalse(AppInstallEvents.emit(payload("cmd-1")))
        assertTrue(seen.isEmpty())
    }
}
