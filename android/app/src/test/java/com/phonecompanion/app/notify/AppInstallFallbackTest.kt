package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * ACTION_VIEW fallback for PackageInstaller commit failures.
 *
 * On some devices the session commit throws even though the APK is intact
 * (the user saw "Install could not start", nothing installed, a dead
 * end). The flow re-verifies the cached APK and fires it at the system's
 * default installer via VIEW instead of posting FAILED; only when the
 * fallback cannot start does the existing FAILED terminal post.
 *
 * Pure JVM (the helper keeps intent shape, authority, path scoping, the
 * trigger gate, and the exactly-one-post disposition as plain values):
 * no emulator, no new dependencies.
 */
class AppInstallFallbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun payload(
        pushId: String = "fallback-push",
        sha256: String = "b".repeat(64),
    ) = AppInstallPayload.fromMap(
        mapOf(
            "command_id" to "cmd-fallback",
            "push_id" to pushId,
            "apk_url" to "https://example.com/app.apk",
            "sha256" to sha256,
            "package" to "com.example.app",
            "label" to "Example",
        ),
    )!!

    /** Local SHA-256 helper (test-only), same shape as AppInstallCryptoTest. */
    private fun sha256Of(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** The expected cached APK for [payload] inside a fake cache dir. */
    private fun cachedApk(cacheDir: File, payload: AppInstallPayload, bytes: ByteArray = ByteArray(10)): File {
        val dir = AppInstallFiles.pushDir(cacheDir)
        dir.mkdirs()
        return File(dir, payload.cacheFileName()).apply { writeBytes(bytes) }
    }

    // Intent shape: action / type / flags / authority-path scoping.

    @Test
    fun `fallback action is view`() {
        // android.intent.action.VIEW, referenced as the Intent constant in prod.
        assertEquals("android.intent.action.VIEW", AppInstallFallback.intentAction())
    }

    @Test
    fun `fallback mime type is the package-archive type`() {
        assertEquals("application/vnd.android.package-archive", AppInstallFallback.MIME_TYPE)
    }

    @Test
    fun `fallback flags grant read and start outside an activity`() {
        val flags = AppInstallFallback.intentFlags()
        // FLAG_GRANT_READ_URI_PERMISSION (0x1) + FLAG_ACTIVITY_NEW_TASK
        // (0x10000000): the cross-uid installer can read the FileProvider
        // URI, and startActivity works from an application context.
        assertTrue(flags and 0x00000001 != 0)
        assertTrue(flags and 0x10000000 != 0)
    }

    @Test
    fun `fallback authority is the app package plus fileprovider`() {
        assertEquals(
            "com.phonecompanion.app.fileprovider",
            AppInstallFallback.authorityFor("com.phonecompanion.app"),
        )
    }

    @Test
    fun `fallback authorities stay per-app`() {
        assertEquals(
            "com.example.other.fileprovider",
            AppInstallFallback.authorityFor("com.example.other"),
        )
    }

    @Test
    fun `push dir is the app_push cache dir the provider paths cover`() {
        // The FileProvider entry (res/xml/app_install_filepaths.xml) covers
        // ONLY this dir — never the whole cache, never external dirs. The
        // name pins the manifest/code agreement in a unit test.
        assertEquals("app_push", AppInstallFiles.pushDir(tmp.root).name)
    }

    // Trigger gate: exactly on commit failure, with a verified file.

    @Test
    fun `fallback triggers exactly on commit failure with a verified file`() {
        assertTrue(
            AppInstallFallback.shouldAttemptFallback(
                AppInstallFlow.Outcome.CommitFailed,
                verifiedFilePresent = true,
            ),
        )
    }

    @Test
    fun `fallback never triggers without a verified file`() {
        assertFalse(
            AppInstallFallback.shouldAttemptFallback(
                AppInstallFlow.Outcome.CommitFailed,
                verifiedFilePresent = false,
            ),
        )
    }

    @Test
    fun `fallback never triggers on non-commit outcomes`() {
        val others = listOf(
            AppInstallFlow.Outcome.Committed,
            AppInstallFlow.Outcome.TooLarge,
            AppInstallFlow.Outcome.Cancelled,
            AppInstallFlow.Outcome.DownloadFailed,
            AppInstallFlow.Outcome.HashMismatch,
            AppInstallFlow.Outcome.UnknownSourcesRevoked,
            AppInstallFlow.Outcome.SilentInstalled,
        )
        for (outcome in others) {
            assertFalse(
                "$outcome must never open the fallback",
                AppInstallFallback.shouldAttemptFallback(outcome, verifiedFilePresent = true),
            )
        }
    }

    // Verified-file requirement: missing/corrupt/off-path files refuse.

    @Test
    fun `expected cached apk passes the scope gate`() {
        val p = payload()
        val file = cachedApk(tmp.root, p)
        assertTrue(AppInstallFallback.isFallbackFile(tmp.root, p, file))
    }

    @Test
    fun `missing file fails the scope gate`() {
        val p = payload()
        assertFalse(
            AppInstallFallback.isFallbackFile(
                tmp.root,
                p,
                File(AppInstallFiles.pushDir(tmp.root), p.cacheFileName()),
            ),
        )
    }

    @Test
    fun `wrong file name fails the scope gate`() {
        val p = payload()
        val dir = AppInstallFiles.pushDir(tmp.root)
        dir.mkdirs()
        val other = File(dir, "other.apk").apply { writeBytes(ByteArray(4)) }
        assertFalse(AppInstallFallback.isFallbackFile(tmp.root, p, other))
    }

    @Test
    fun `same name outside the push dir fails the scope gate`() {
        val p = payload()
        cachedApk(tmp.root, p)
        // Same sanitized name, but a sibling of app_push (traversal-shaped):
        // canonical comparison refuses.
        val sibling = File(tmp.root, p.cacheFileName()).apply { writeBytes(ByteArray(10)) }
        assertFalse(AppInstallFallback.isFallbackFile(tmp.root, p, sibling))
    }

    @Test
    fun `other push id fails the scope gate`() {
        val p = payload(pushId = "push-one")
        val file = cachedApk(tmp.root, p)
        assertFalse(AppInstallFallback.isFallbackFile(tmp.root, payload(pushId = "push-two"), file))
    }

    @Test
    fun `corrupt bytes fail re-verify so the fallback never fires on them`() {
        val bytes = "fake-apk-bytes".toByteArray()
        val p = payload(sha256 = sha256Of(bytes))
        val file = cachedApk(tmp.root, p, bytes)
        assertTrue(AppInstallCrypto.verifyFile(file, p.sha256))
        file.writeBytes("tampered-bytes".toByteArray())
        // Scope still passes (right path, right name) but the bytes no
        // longer verify: fireFallback's re-verify refuses, and the
        // disposition below posts FAILED instead of a VIEW at bad bytes.
        assertTrue(AppInstallFallback.isFallbackFile(tmp.root, p, file))
        assertFalse(AppInstallCrypto.verifyFile(file, p.sha256))
    }

    // Exactly one user-visible post per attempt.

    @Test
    fun `disposition opens the fallback only with verified file and started intent`() {
        assertEquals(
            AppInstallFallback.CommitFailedDisposition.OPENED_SYSTEM_INSTALLER,
            AppInstallFallback.dispositionForCommitFailed(
                verifiedFilePresent = true,
                fallbackStarted = true,
            ),
        )
    }

    @Test
    fun `disposition posts failed when the file or the intent is missing`() {
        for ((verified, started) in listOf(
            true to false,
            false to true,
            false to false,
        )) {
            assertEquals(
                "verified=$verified started=$started must post FAILED",
                AppInstallFallback.CommitFailedDisposition.POST_FAILED,
                AppInstallFallback.dispositionForCommitFailed(verified, started),
            )
        }
    }

    @Test
    fun `fallback note and failed terminal share one per-attempt claim`() {
        // Both arms of the CommitFailed funnel claim through
        // claimTerminal: exactly one caller per attempt posts, so a racing
        // path (cancel/activity/result receiver) stays silent.
        val pushId = "fallback-claim-${UUID.randomUUID()}"
        val attempt = "attempt-${UUID.randomUUID()}"
        try {
            assertTrue(AppInstallSessions.claimTerminal(pushId, attempt))
            assertFalse(AppInstallSessions.claimTerminal(pushId, attempt))
        } finally {
            AppInstallSessions.clearForTest(pushId)
        }
    }
}
