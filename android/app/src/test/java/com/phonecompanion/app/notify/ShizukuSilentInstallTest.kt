package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Shizuku silent-install gateway ([ShizukuSilentInstall]):
 * status classification, the ask-once affordance rule, shell-result
 * parsing, and the staged-path sanity check. Pure JVM — the Android
 * Shizuku statics stay behind [ShizukuSilentInstall.Probe], faked here.
 */
class ShizukuSilentInstallTest {

    private data class FakeProbe(
        val supported: Boolean = true,
        val alive: Boolean = true,
        val granted: Boolean = false,
        val rationale: Boolean = false,
        val throwOn: String? = null,
    ) : ShizukuSilentInstall.Probe {
        private fun maybeThrow(which: String) {
            if (throwOn == which) throw IllegalStateException("binder gone")
        }

        override fun isSupported(): Boolean {
            maybeThrow("supported")
            return supported
        }

        override fun isBinderAlive(): Boolean {
            maybeThrow("alive")
            return alive
        }

        override fun isPermissionGranted(): Boolean {
            maybeThrow("granted")
            return granted
        }

        override fun shouldShowRationale(): Boolean {
            maybeThrow("rationale")
            return rationale
        }
    }

    @Test
    fun `pre-v11 is unavailable even with a live binder`() {
        assertEquals(
            ShizukuSilentInstall.Status.UNAVAILABLE,
            ShizukuSilentInstall.statusOf(FakeProbe(supported = false)),
        )
    }

    @Test
    fun `dead binder is unavailable even when granted`() {
        assertEquals(
            ShizukuSilentInstall.Status.UNAVAILABLE,
            ShizukuSilentInstall.statusOf(FakeProbe(alive = false, granted = true)),
        )
    }

    @Test
    fun `denied and granted classify distinctly`() {
        assertEquals(
            ShizukuSilentInstall.Status.AVAILABLE_DENIED,
            ShizukuSilentInstall.statusOf(FakeProbe(granted = false)),
        )
        assertEquals(
            ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            ShizukuSilentInstall.statusOf(FakeProbe(granted = true)),
        )
    }

    @Test
    fun `probe exceptions fail closed to unavailable`() {
        for (which in listOf("supported", "alive")) {
            assertEquals(
                ShizukuSilentInstall.Status.UNAVAILABLE,
                ShizukuSilentInstall.statusOf(FakeProbe(granted = true, throwOn = which)),
            )
        }
        // A grant-probe failure fails closed to the dialog path (denied),
        // not to unavailable — the binder may be fine.
        assertEquals(
            ShizukuSilentInstall.Status.AVAILABLE_DENIED,
            ShizukuSilentInstall.statusOf(FakeProbe(granted = true, throwOn = "granted")),
        )
    }

    @Test
    fun `enable affordance shows only while askable`() {
        // Binder alive + missing + not don't-ask-again: offer once.
        assertTrue(ShizukuSilentInstall.shouldOfferRequest(FakeProbe(granted = false, rationale = false)))
        // Shizuku rationale true means "Deny and don't ask again": never nag.
        assertFalse(ShizukuSilentInstall.shouldOfferRequest(FakeProbe(granted = false, rationale = true)))
        // Granted: nothing to offer (silent path active).
        assertFalse(ShizukuSilentInstall.shouldOfferRequest(FakeProbe(granted = true)))
        // Unavailable (pre-v11 or dead binder): never offer.
        assertFalse(ShizukuSilentInstall.shouldOfferRequest(FakeProbe(supported = false)))
        assertFalse(ShizukuSilentInstall.shouldOfferRequest(FakeProbe(alive = false)))
        // Rationale probe throwing fails closed to hidden.
        assertFalse(ShizukuSilentInstall.shouldOfferRequest(FakeProbe(throwOn = "rationale")))
    }

    @Test
    fun `shell result parses exit code and preserves pm output`() {
        val ok = ShizukuSilentInstall.parseShellResult("exit:0\nSuccess\n")
        assertEquals(0, ok.exitCode)
        assertEquals("Success\n", ok.output)

        val pmFailure = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: " +
            "Package com.example.app signatures do not match previously installed version]"
        val failed = ShizukuSilentInstall.parseShellResult("exit:1\n$pmFailure")
        assertEquals(1, failed.exitCode)
        assertEquals(pmFailure, failed.output)
    }

    @Test
    fun `malformed shell result is a failure never a throw`() {
        assertEquals(-1, ShizukuSilentInstall.parseShellResult(null).exitCode)
        assertEquals(-1, ShizukuSilentInstall.parseShellResult("").exitCode)
        assertEquals(-1, ShizukuSilentInstall.parseShellResult("Success").exitCode)
        assertEquals(-1, ShizukuSilentInstall.parseShellResult("exit:oops\nx").exitCode)
    }

    @Test
    fun `shell output is capped`() {
        val big = "x".repeat(ShizukuSilentInstall.MAX_SHELL_OUTPUT_CHARS + 100)
        val parsed = ShizukuSilentInstall.parseShellResult("exit:1\n$big")
        assertEquals(1, parsed.exitCode)
        assertEquals(ShizukuSilentInstall.MAX_SHELL_OUTPUT_CHARS, parsed.output.length)
    }

    @Test
    fun `staged path sanity rejects shapes the flow never stages`() {
        assertTrue(ShizukuSilentInstall.isStagedApkPath("/storage/emulated/0/app.apk"))
        assertTrue(ShizukuSilentInstall.isStagedApkPath("/x/y/app-push-abc.apk"))
        assertFalse(ShizukuSilentInstall.isStagedApkPath(""))
        assertFalse(ShizukuSilentInstall.isStagedApkPath("relative.apk"))
        assertFalse(ShizukuSilentInstall.isStagedApkPath("/x/app.zip"))
        assertFalse(ShizukuSilentInstall.isStagedApkPath("/x/app.apk\npm install -r /evil.apk"))
        assertFalse(ShizukuSilentInstall.isStagedApkPath("/x/app.apk\u0000"))
    }
}
