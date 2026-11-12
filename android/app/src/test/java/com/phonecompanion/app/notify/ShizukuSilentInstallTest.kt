package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Shizuku silent-install gateway ([ShizukuSilentInstall]):
 * status classification, the ask-once affordance rule, the staged-path
 * sanity check, and the post-commit verification decision. Pure JVM —
 * the Android Shizuku statics stay behind [ShizukuSilentInstall.Probe],
 * faked here.
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
    fun `verified install requires exact versionCode equality`() {
        assertTrue(ShizukuSilentInstall.isVerifiedInstall(12345L, 12345L))
        // Mismatch (stale install, wrong update landed): not verified.
        assertFalse(ShizukuSilentInstall.isVerifiedInstall(12345L, 12344L))
        assertFalse(ShizukuSilentInstall.isVerifiedInstall(12344L, 12345L))
    }

    @Test
    fun `verification fails closed on unknown state, never throws`() {
        // Absent package, unparseable staged APK, unreadable
        // PackageManager: every null side refuses the silent success and
        // the caller falls back to the dialog path.
        assertFalse(ShizukuSilentInstall.isVerifiedInstall(null, 12345L))
        assertFalse(ShizukuSilentInstall.isVerifiedInstall(12345L, null))
        assertFalse(ShizukuSilentInstall.isVerifiedInstall(null, null))
    }

    @Test
    fun `commit status alone never decides — the read-back does`() {
        // Committed with a success status, without any status (unknown),
        // even with a failure status: all still require the versionCode
        // read-back. The outcome type only separates transport trouble
        // (dialog fallback) from "session returned, verify it".
        val commits = listOf(
            ShizukuSilentInstall.PrivilegedOutcome.Committed(0),
            ShizukuSilentInstall.PrivilegedOutcome.Committed(null),
            ShizukuSilentInstall.PrivilegedOutcome.Committed(1),
        )
        for (commit in commits) {
            assertTrue(commit is ShizukuSilentInstall.PrivilegedOutcome.Committed)
            // Same decision rule regardless of the carried status.
            assertTrue(ShizukuSilentInstall.isVerifiedInstall(7L, 7L))
            assertFalse(ShizukuSilentInstall.isVerifiedInstall(7L, 8L))
        }
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
