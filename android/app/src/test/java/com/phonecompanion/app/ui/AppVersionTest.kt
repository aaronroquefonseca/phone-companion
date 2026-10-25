package com.phonecompanion.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * About-screen build identity: versionName/versionCode plus the build-time
 * git SHA, with a "dev" fallback wherever build metadata is missing, so
 * future bug reports identify the installed build.
 */
class AppVersionTest {

    @Test
    fun `line names version code and sha`() {
        assertEquals("Version 0.1.0-skeleton (1) · abc1234", AppVersion.line("0.1.0-skeleton", 1, "abc1234"))
    }

    @Test
    fun `blank version name falls back to dev`() {
        assertEquals("Version dev (1) · abc1234", AppVersion.line("", 1, "abc1234"))
        assertEquals("Version dev (1) · abc1234", AppVersion.line(null, 1, "abc1234"))
    }

    @Test
    fun `missing sha falls back to dev`() {
        assertEquals("Version 1.2.3 (42) · dev", AppVersion.line("1.2.3", 42, ""))
        assertEquals("Version 1.2.3 (42) · dev", AppVersion.line("1.2.3", 42, null))
    }

    @Test
    fun `line is never blank`() {
        assertTrue(AppVersion.line(null, 0, null).isNotBlank())
    }
}
