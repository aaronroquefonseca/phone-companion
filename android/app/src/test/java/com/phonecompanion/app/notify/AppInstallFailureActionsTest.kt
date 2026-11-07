package com.phonecompanion.app.notify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Cause-aware install-failure behavior.
 *
 * A pushed update built with a different key than the installed build
 * fails at commit with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — and the
 * failure notification used to be a dead end (no action). Retrying the
 * same bytes would fail deterministically, so the mapping is cause-aware:
 * signature mismatch offers the target's system App-info screen (where
 * Uninstall lives) with NO Retry; any other commit/install failure is
 * treated as transient and offers Retry, which resumes via
 * [AppInstallFlow.runResume] (verified cache reused, re-download only on
 * a miss). User-cancelled and hash-mismatch behavior is locked unchanged.
 *
 * Pure JVM (the mapping lives in [AppInstallResult], the resume path in
 * [AppInstallFlow]): no emulator, no new dependencies.
 */
class AppInstallFailureActionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun payload() = AppInstallPayload.fromMap(
        mapOf(
            "command_id" to "cmd-failact",
            "push_id" to "failact-push",
            "apk_url" to "https://example.com/app.apk",
            "sha256" to "b".repeat(64),
            "package" to "com.example.app",
            "label" to "Example",
        ),
    )!!

    /** Minimal fake host recording download/commit counts. */
    private class FakeHost(
        var downloadResult: AppInstallFlow.DownloadResult = AppInstallFlow.DownloadResult.Done(10),
        var verifyResult: Boolean = true,
        var canInstallResult: Boolean = true,
        var commitResult: Boolean = true,
        var commitThrows: Boolean = false,
        var cancelled: Boolean = false,
    ) : AppInstallFlow.Host {
        var downloadCalls = 0
        var commitCalls = 0

        override suspend fun download(
            payload: AppInstallPayload,
            dest: File,
            onProgress: (Long, Long?) -> Unit,
        ): AppInstallFlow.DownloadResult {
            downloadCalls++
            if (downloadResult is AppInstallFlow.DownloadResult.Done) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(10))
            }
            return downloadResult
        }

        override suspend fun verify(file: File, sha256: String): Boolean = verifyResult

        override fun canInstall(): Boolean = canInstallResult

        override fun isCancelled(): Boolean = cancelled

        override suspend fun commit(payload: AppInstallPayload, apkFile: File): Boolean {
            commitCalls++
            if (commitThrows) throw RuntimeException("installer unavailable")
            return commitResult
        }
    }

    private fun dest(): File = File(tmp.root, "dl.apk")

    // Cause mapping: signature mismatch.

    @Test
    fun `canonical update-incompatible message maps to signature mismatch`() {
        assertEquals(
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH,
            AppInstallResult.failureKindOf(
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE: " +
                    "Package com.example.app signatures do not match " +
                    "previously installed version; ignoring!",
            ),
        )
    }

    @Test
    fun `bare update-incompatible marker maps to signature mismatch`() {
        assertEquals(
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH,
            AppInstallResult.failureKindOf("UPDATE_INCOMPATIBLE"),
        )
    }

    @Test
    fun `signatures-do-not-match alone maps to signature mismatch`() {
        assertEquals(
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH,
            AppInstallResult.failureKindOf("signatures do not match the installed build"),
        )
    }

    @Test
    fun `signature matching is case insensitive`() {
        assertEquals(
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH,
            AppInstallResult.failureKindOf("Signatures Do Not Match Previously Installed Version"),
        )
        assertEquals(
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH,
            AppInstallResult.failureKindOf("install_failed_update_incompatible"),
        )
    }

    // Cause mapping: transient.

    @Test
    fun `null blank and unrelated messages map to transient`() {
        for (message in listOf(
            null,
            "",
            "   ",
            "INSTALL_FAILED_INSUFFICIENT_STORAGE",
            "INSTALL_FAILED_INVALID_APK: Failed to extract native libraries",
            "Failed to create install session",
        )) {
            assertEquals(
                "message <$message> should be transient",
                AppInstallResult.FailureKind.TRANSIENT,
                AppInstallResult.failureKindOf(message),
            )
        }
    }

    // Expected actions per cause.

    @Test
    fun `signature mismatch offers app-info only, never retry`() {
        assertEquals(
            setOf(AppInstallResult.FailureAction.APP_INFO),
            AppInstallResult.failureActions(AppInstallResult.FailureKind.SIGNATURE_MISMATCH),
        )
    }

    @Test
    fun `transient failure offers retry only, never app-info`() {
        assertEquals(
            setOf(AppInstallResult.FailureAction.RETRY),
            AppInstallResult.failureActions(AppInstallResult.FailureKind.TRANSIENT),
        )
    }

    // Retry resumes from the cached verified APK (the runResume path).

    @Test
    fun `retry reuses an intact verified cache without re-downloading`() = runBlocking {
        val host = FakeHost()
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(0, host.downloadCalls)
        assertEquals(1, host.commitCalls)
    }

    @Test
    fun `retry falls back to a full download on cache miss`() = runBlocking {
        val host = FakeHost()
        val outcome = AppInstallFlow.runResume(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(1, host.downloadCalls)
        assertEquals(1, host.commitCalls)
    }

    @Test
    fun `commit failure keeps the verified apk for retry`() = runBlocking {
        val host = FakeHost(commitResult = false)
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.CommitFailed, outcome)
        assertTrue(file.exists())
    }

    @Test
    fun `commit exception keeps the verified apk for retry`() = runBlocking {
        val host = FakeHost(commitThrows = true)
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.CommitFailed, outcome)
        assertTrue(file.exists())
    }

    // Unchanged behavior: cancel and mismatch.

    @Test
    fun `user abort still classifies as cancelled, never failed`() {
        assertEquals(
            AppInstallResult.Outcome.UserCancelled,
            AppInstallResult.classify(AppInstallResult.STATUS_FAILURE_ABORTED, "abort"),
        )
    }

    @Test
    fun `cancel during commit still surfaces as Cancelled`() = runBlocking {
        val host = FakeHost(commitResult = false, cancelled = true)
        assertEquals(AppInstallFlow.Outcome.Cancelled, AppInstallFlow.runFlow(payload(), dest(), host))
    }

    @Test
    fun `hash mismatch still deletes the file and stays action-less`() = runBlocking {
        val host = FakeHost(verifyResult = false)
        val file = dest()
        file.writeBytes(ByteArray(10))
        assertEquals(AppInstallFlow.Outcome.HashMismatch, AppInstallFlow.runFlow(payload(), file, host))
        assertFalse(file.exists())
        with(AppInstallFlow) {
            assertEquals(
                AppInstallFlow.TerminalKind.HASH_MISMATCH,
                AppInstallFlow.Outcome.HashMismatch.terminalKind(),
            )
        }
    }
}
