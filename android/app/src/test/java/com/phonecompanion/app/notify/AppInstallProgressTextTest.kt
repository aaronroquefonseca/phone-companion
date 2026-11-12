package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unknown-length progress line: bytes must read as a human size. */
class AppInstallProgressTextTest {

    @Test
    fun `megabytes render with one decimal`() {
        assertEquals("1.5 MB", AppInstallNotifications.formatDownloaded(1_572_864))
    }

    @Test
    fun `kilobytes render below one megabyte`() {
        assertEquals("512 KB", AppInstallNotifications.formatDownloaded(524_288))
    }
}
