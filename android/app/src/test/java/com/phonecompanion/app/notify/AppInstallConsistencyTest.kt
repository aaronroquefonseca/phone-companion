package com.phonecompanion.app.notify

import com.phonecompanion.app.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-owner consistency: one notifyId derivation, one general channel
 * id shared by the generic path and the install flow, and a logging-free
 * download stack (APK URLs may carry signed query tokens).
 */
class AppInstallConsistencyTest {

    @Test
    fun `notify id has a single owner`() {
        val p = AppInstallPayload.fromMap(
            mapOf(
                "push_id" to "push-9",
                "apk_url" to "https://example.com/app.apk",
                "sha256" to "c".repeat(64),
                "package" to "com.example.app",
                "label" to "Example",
            ),
        )!!
        assertEquals(AppInstallPayload.notifyIdFor("push-9"), p.notifyId())
        assertEquals(("app_install:push-9").hashCode(), p.notifyId())
    }

    @Test
    fun `general channel id has a single owner`() {
        assertEquals(NotificationPayload.GENERAL_CHANNEL_ID, NotificationChannels.GENERAL_CHANNEL_ID)
        assertEquals("pc_general", NotificationChannels.GENERAL_CHANNEL_ID)
        assertEquals("General notifications", NotificationKinds.displayName("unknown"))
    }

    @Test
    fun `download client carries no interceptors and no logging`() {
        // Signed APK URLs must never reach logcat and the device Bearer
        // token must never be sent to a non-server host: zero
        // interceptors of any kind on the download stack.
        val client = ApiClient.downloadClient()
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
    }

    @Test
    fun `terminal detail truncation is a named bound`() {
        assertEquals(200, AppInstallNotifications.MAX_DETAIL_CHARS)
    }
}
