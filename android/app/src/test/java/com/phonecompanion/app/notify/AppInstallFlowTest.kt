package com.phonecompanion.app.notify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the install flow entries ([AppInstallFlow.runFlow] unconditional
 * download, [AppInstallFlow.runResume] verified-cache reuse): download →
 * verify → unknown-sources re-check → commit ordering, the grant-resume
 * paths, fail-closed outcomes, and the terminal-funnel mapping. The
 * Android host is faked; the real hosts live in AppInstallViewModel and
 * AppInstallAutoDownload and only adapt calls.
 */
class AppInstallFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun payload() = AppInstallPayload.fromMap(
        mapOf(
            "command_id" to "cmd-flow",
            "push_id" to "flow-push",
            "apk_url" to "https://example.com/app.apk",
            "sha256" to "b".repeat(64),
            "package" to "com.example.app",
            "label" to "Example",
        ),
    )!!

    /** Fake host recording the call order for order assertions. */
    private class FakeHost(
        var downloadResult: AppInstallFlow.DownloadResult = AppInstallFlow.DownloadResult.Done(10),
        var verifyResult: Boolean = true,
        var canInstallResult: Boolean = true,
        var commitResult: Boolean = true,
        var cancelled: Boolean = false,
    ) : AppInstallFlow.Host {
        val events = mutableListOf<String>()
        var downloadCalls = 0
        var commitCalls = 0
        var dest: File? = null

        override suspend fun download(
            payload: AppInstallPayload,
            dest: File,
            onProgress: (Long, Long?) -> Unit,
        ): AppInstallFlow.DownloadResult {
            events += "download"
            downloadCalls++
            this.dest = dest
            if (downloadResult is AppInstallFlow.DownloadResult.Done) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(10))
                onProgress(10, 10)
            }
            return downloadResult
        }

        override suspend fun verify(file: File, sha256: String): Boolean {
            events += "verify"
            return verifyResult
        }

        override fun canInstall(): Boolean {
            events += "canInstall"
            return canInstallResult
        }

        override fun isCancelled(): Boolean = cancelled

        override suspend fun commit(payload: AppInstallPayload, apkFile: File): Boolean {
            events += "commit"
            commitCalls++
            return commitResult
        }
    }

    private fun dest(): File = File(tmp.root, "dl.apk")

    @Test
    fun `happy path runs download verify recheck commit in order`() = runBlocking {
        val host = FakeHost()
        val outcome = AppInstallFlow.runFlow(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        // The unknown-sources re-check runs AFTER verify and BEFORE commit.
        assertEquals(listOf("download", "verify", "canInstall", "commit"), host.events)
    }

    @Test
    fun `unknown sources revoked after verify stops fail closed with no commit`() = runBlocking {
        val host = FakeHost(canInstallResult = false)
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.UnknownSourcesRevoked, outcome)
        assertEquals(listOf("download", "verify", "canInstall"), host.events)
        assertEquals(0, host.commitCalls)
        // Fail closed: no installable artifact lingers.
        assertFalse(file.exists())
    }

    @Test
    fun `hash mismatch deletes the file and never commits`() = runBlocking {
        val host = FakeHost(verifyResult = false)
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.HashMismatch, outcome)
        assertEquals(0, host.commitCalls)
        assertFalse(file.exists())
    }

    @Test
    fun `cancel before commit surfaces as Cancelled not mismatch or failure`() = runBlocking {
        // Cancel landing mid-verify: verify misses, but the flow reports
        // the cancellation it was.
        val host = FakeHost(verifyResult = false, cancelled = true)
        val outcome = AppInstallFlow.runFlow(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Cancelled, outcome)
        assertEquals(0, host.commitCalls)
    }

    @Test
    fun `cancel during commit surfaces as Cancelled`() = runBlocking {
        val host = FakeHost(commitResult = false, cancelled = true)
        val outcome = AppInstallFlow.runFlow(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Cancelled, outcome)
    }

    @Test
    fun `commit failure without cancel is CommitFailed`() = runBlocking {
        val host = FakeHost(commitResult = false)
        val outcome = AppInstallFlow.runFlow(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.CommitFailed, outcome)
    }

    @Test
    fun `download outcomes map without verify or commit`() = runBlocking {
        for ((result, expected) in listOf(
            AppInstallFlow.DownloadResult.TooLarge to AppInstallFlow.Outcome.TooLarge,
            AppInstallFlow.DownloadResult.Cancelled to AppInstallFlow.Outcome.Cancelled,
            AppInstallFlow.DownloadResult.Failed to AppInstallFlow.Outcome.DownloadFailed,
        )) {
            val host = FakeHost(downloadResult = result)
            assertEquals(expected, AppInstallFlow.runFlow(payload(), dest(), host))
            assertEquals(listOf("download"), host.events)
        }
    }

    @Test
    fun `host exceptions fail closed`() = runBlocking {
        val failing = object : AppInstallFlow.Host by FakeHost() {
            override suspend fun download(
                payload: AppInstallPayload,
                dest: File,
                onProgress: (Long, Long?) -> Unit,
            ): AppInstallFlow.DownloadResult = throw RuntimeException("net down")
        }
        assertEquals(AppInstallFlow.Outcome.DownloadFailed, AppInstallFlow.runFlow(payload(), dest(), failing))
    }

    @Test
    fun `terminal funnel mapping covers every outcome`() {
        with(AppInstallFlow) {
            assertNull(AppInstallFlow.Outcome.Committed.terminalKind())
            assertNull(AppInstallFlow.Outcome.UnknownSourcesRevoked.terminalKind())
            assertEquals(
                AppInstallFlow.TerminalKind.TOO_LARGE,
                AppInstallFlow.Outcome.TooLarge.terminalKind(),
            )
            assertEquals(
                AppInstallFlow.TerminalKind.DOWNLOAD_CANCELLED,
                AppInstallFlow.Outcome.Cancelled.terminalKind(),
            )
            assertEquals(
                AppInstallFlow.TerminalKind.DOWNLOAD_FAILED,
                AppInstallFlow.Outcome.DownloadFailed.terminalKind(),
            )
            assertEquals(
                AppInstallFlow.TerminalKind.HASH_MISMATCH,
                AppInstallFlow.Outcome.HashMismatch.terminalKind(),
            )
            assertEquals(
                AppInstallFlow.TerminalKind.FAILED,
                AppInstallFlow.Outcome.CommitFailed.terminalKind(),
            )
        }
    }

    @Test
    fun `detail-on-tap only for the retryable terminal kinds`() {
        with(AppInstallFlow) {
            assertTrue(AppInstallFlow.TerminalKind.FAILED.opensDetailOnTap)
            assertTrue(AppInstallFlow.TerminalKind.DOWNLOAD_FAILED.opensDetailOnTap)
            assertFalse(AppInstallFlow.TerminalKind.SUCCESS.opensDetailOnTap)
            assertFalse(AppInstallFlow.TerminalKind.USER_CANCELLED.opensDetailOnTap)
            assertFalse(AppInstallFlow.TerminalKind.HASH_MISMATCH.opensDetailOnTap)
            assertFalse(AppInstallFlow.TerminalKind.TOO_LARGE.opensDetailOnTap)
            assertFalse(AppInstallFlow.TerminalKind.DOWNLOAD_CANCELLED.opensDetailOnTap)
        }
    }

    @Test
    fun `keepVerifiedOnRevoked keeps the file for grant-resume`() = runBlocking {
        val host = FakeHost(canInstallResult = false)
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runFlow(payload(), file, host, keepVerifiedOnRevoked = true)
        assertEquals(AppInstallFlow.Outcome.UnknownSourcesRevoked, outcome)
        assertEquals(0, host.commitCalls)
        // Grant-gated auto-download: the verified APK stays cached so the
        // flow resumes after the one-time grant instead of re-fetching.
        assertTrue(file.exists())
    }

    @Test
    fun `resume reuses a verified cache without downloading`() = runBlocking {
        val host = FakeHost()
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(0, host.downloadCalls)
        assertEquals(1, host.commitCalls)
        // No download: straight to the pre-commit gate → commit tail.
        assertEquals(listOf("verify", "canInstall", "commit"), host.events)
    }

    @Test
    fun `resume falls back to full flow on cache miss`() = runBlocking {
        val host = FakeHost()
        val outcome = AppInstallFlow.runResume(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(1, host.downloadCalls)
        assertEquals(listOf("download", "verify", "canInstall", "commit"), host.events)
    }

    @Test
    fun `resume falls back to full flow on verify miss`() = runBlocking {
        // Stale cache (re-verify fails): re-download, then the fresh file
        // fails verify too → HashMismatch, file deleted, no commit.
        val host = FakeHost(verifyResult = false)
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.HashMismatch, outcome)
        assertEquals(1, host.downloadCalls)
        assertEquals(0, host.commitCalls)
        assertFalse(file.exists())
    }

    @Test
    fun `resume cancelled up front never touches the host`() = runBlocking {
        val host = FakeHost(cancelled = true)
        val outcome = AppInstallFlow.runResume(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Cancelled, outcome)
        assertTrue(host.events.isEmpty())
        assertEquals(0, host.downloadCalls)
        assertEquals(0, host.commitCalls)
    }

    @Test
    fun `resume honors the grant gate before commit`() = runBlocking {
        val host = FakeHost(canInstallResult = false)
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.UnknownSourcesRevoked, outcome)
        assertEquals(0, host.downloadCalls)
        assertEquals(0, host.commitCalls)
    }
}
