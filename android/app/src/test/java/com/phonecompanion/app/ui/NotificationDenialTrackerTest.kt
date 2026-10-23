package com.phonecompanion.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDenialTrackerTest {

    @Test
    fun `denial persists through the write lambda and reloads`() {
        val backing = mutableMapOf<String, Boolean>()
        val first = NotificationDenialTracker(
            readPersisted = { backing["denied"] ?: false },
            writePersisted = { backing["denied"] = it },
        )
        assertFalse(first.deniedBefore)
        first.onPermissionResult(granted = false)
        assertTrue(first.deniedBefore)
        assertEquals(true, backing["denied"])

        // A new tracker (new process) reads the persisted denial.
        val second = NotificationDenialTracker(
            readPersisted = { backing["denied"] ?: false },
            writePersisted = { backing["denied"] = it },
        )
        assertTrue(second.deniedBefore)
    }

    @Test
    fun `grant clears the persisted denial`() {
        val backing = mutableMapOf("denied" to true)
        val tracker = NotificationDenialTracker(
            readPersisted = { backing["denied"] ?: false },
            writePersisted = { backing["denied"] = it },
        )
        assertTrue(tracker.deniedBefore)
        tracker.onPermissionResult(granted = true)
        assertFalse(tracker.deniedBefore)
        assertEquals(false, backing["denied"])
    }
}
