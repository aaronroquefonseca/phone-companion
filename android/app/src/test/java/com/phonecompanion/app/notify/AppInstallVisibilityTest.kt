package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the install-flow visibility decision (notification vs in-app
 * indicator) and the in-flight progress registry the in-app surfaces read.
 * Pure JVM: the platform edge ([AppInstallVisibility.canPostNotifications]
 * over a Context) is not exercised here — only the decision table.
 */
class AppInstallVisibilityTest {

    @Test
    fun `notification is the source of truth when grant and channel allow`() {
        assertTrue(AppInstallVisibility.canPostNotifications(sdkInt = 33, notificationsGranted = true, channelBlocked = false))
        // Pre-33 there is no runtime permission; only the channel matters.
        assertTrue(AppInstallVisibility.canPostNotifications(sdkInt = 32, notificationsGranted = false, channelBlocked = false))
    }

    @Test
    fun `denied permission on API 33+ hides the notification`() {
        assertFalse(AppInstallVisibility.canPostNotifications(sdkInt = 33, notificationsGranted = false, channelBlocked = false))
    }

    @Test
    fun `a blocked channel hides the notification on every version`() {
        assertFalse(AppInstallVisibility.canPostNotifications(sdkInt = 33, notificationsGranted = true, channelBlocked = true))
        assertFalse(AppInstallVisibility.canPostNotifications(sdkInt = 26, notificationsGranted = true, channelBlocked = true))
    }

    @Test
    fun `a min-importance channel is invisible and hides the notification`() {
        assertFalse(
            AppInstallVisibility.canPostNotifications(
                sdkInt = 33,
                notificationsGranted = true,
                channelBlocked = false,
                channelMin = true,
            ),
        )
    }

    @Test
    fun `app-level notifications disabled hides the notification`() {
        assertFalse(
            AppInstallVisibility.canPostNotifications(
                sdkInt = 33,
                notificationsGranted = true,
                channelBlocked = false,
                appNotificationsEnabled = false,
            ),
        )
    }

    @Test
    fun `progress registry set get and clear`() {
        try {
            AppInstallProgress.set("p1", "com.example.app", "Example", 42)
            val entry = AppInstallProgress.get("p1")
            assertEquals("com.example.app", entry?.packageName)
            assertEquals(42, entry?.percent)
            AppInstallProgress.clear("p1")
            assertNull(AppInstallProgress.get("p1"))
        } finally {
            AppInstallProgress.clearForTest()
        }
    }

    @Test
    fun `progress registry reports all active downloads and null percent`() {
        try {
            AppInstallProgress.set("p1", "com.a", "A", null)
            AppInstallProgress.set("p2", "com.b", "B", 75)
            val active = AppInstallProgress.active().associateBy { it.pushId }
            assertEquals(2, active.size)
            assertNull(active["p1"]?.percent)
            assertEquals(75, active["p2"]?.percent)
            AppInstallProgress.clear("p1")
            assertEquals(listOf("p2"), AppInstallProgress.active().map { it.pushId })
        } finally {
            AppInstallProgress.clearForTest()
        }
    }
}
