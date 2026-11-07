package com.phonecompanion.app.notify

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PackageInstaller result-intent mutability.
 *
 * The installer fills status extras into our result intent via fillIn,
 * which an IMMUTABLE PendingIntent drops — so API 31+ (where mutability
 * must be declared) needs MUTABLE or the receiver never sees extras.
 */
class AppInstallInstallerTest {

    @Test
    fun `result flags are mutable on API 31 plus`() {
        val flags = AppInstallInstaller.resultIntentFlags(31)
        assertNotEquals(0, flags and PendingIntent.FLAG_MUTABLE)
        assertNotEquals(0, flags and PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Test
    fun `result flags stay immutable-compatible below API 31`() {
        val flags = AppInstallInstaller.resultIntentFlags(30)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, flags)
    }
}
