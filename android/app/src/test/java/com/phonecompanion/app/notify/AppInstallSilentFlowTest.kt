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
 * Covers the Shizuku silent branch of [AppInstallFlow] (verify → stage →
 * shell commit): when it is attempted, when it falls back to the dialog
 * tail, and what each terminal leaves on disk. The Android hosts (auto +
 * activity) only adapt calls; the ordering and fallback rules live here
 * and are faked without a device.
 */
class AppInstallSilentFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun payload() = AppInstallPayload.fromMap(
        mapOf(
            "command_id" to "cmd-silent",
            "push_id" to "silent-push",
            "apk_url" to "https://example.com/app.apk",
            "sha256" to "d".repeat(64),
            "package" to "com.example.app",
            "label" to "Example",
        ),
    )!!

    /** Fake host with the silent branch scripted (status/stage/shell). */
    private class SilentFakeHost(
        var downloadResult: AppInstallFlow.DownloadResult = AppInstallFlow.DownloadResult.Done(10),
        var verifyResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
        var canInstallResult: Boolean = true,
        var commitResult: Boolean = true,
        var cancelled: Boolean = false,
        var status: ShizukuSilentInstall.Status =
            ShizukuSilentInstall.Status.UNAVAILABLE,
        /** Null stages nothing (staging impossible → dialog fallback). */
        var staged: File? = null,
        var shellResult: ShizukuSilentInstall.ShellOutcome =
            ShizukuSilentInstall.ShellOutcome.Finished(0, "Success"),
    ) : AppInstallFlow.Host {
        val events = mutableListOf<String>()
        var downloadCalls = 0
        var commitCalls = 0
        var shellStaged: File? = null

        override suspend fun download(
            payload: AppInstallPayload,
            dest: File,
            onProgress: (Long, Long?) -> Unit,
        ): AppInstallFlow.DownloadResult {
            events += "download"
            downloadCalls++
            if (downloadResult is AppInstallFlow.DownloadResult.Done) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(10))
                onProgress(10, 10)
            }
            return downloadResult
        }

        override suspend fun verify(file: File, sha256: String): Boolean {
            events += "verify"
            return if (verifyResults.isEmpty()) true else verifyResults.removeFirst()
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

        override fun silentStatus(): ShizukuSilentInstall.Status = status

        override suspend fun stageForShell(verifiedApk: File): File? {
            events += "stage"
            return staged
        }

        override suspend fun shellCommit(stagedApk: File): ShizukuSilentInstall.ShellOutcome {
            events += "shell"
            shellStaged = stagedApk
            return shellResult
        }
    }

    private fun dest(): File = File(tmp.root, "dl.apk")

    private fun stagedFile(): File = File(tmp.root, "staged.apk").apply {
        writeBytes(ByteArray(10))
    }

    @Test
    fun `granted goes silent with no grant gate and no dialog commit`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.SilentInstalled, outcome)
        // Verify-before-stage ordering, and the dialog tail (grant
        // re-check + PackageInstaller commit) is never touched.
        assertEquals(listOf("download", "verify", "stage", "shell"), host.events)
        assertEquals(staged, host.shellStaged)
        assertEquals(0, host.commitCalls)
        // Silent success deletes both copies: nothing installable lingers.
        assertFalse(file.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun `denied stays on the dialog path untouched`() = runBlocking {
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_DENIED,
            staged = stagedFile(),
        )
        val outcome = AppInstallFlow.runFlow(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(listOf("download", "verify", "canInstall", "commit"), host.events)
        assertEquals(1, host.commitCalls)
    }

    @Test
    fun `binder death falls back to the dialog path from the verified cache`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            shellResult = ShizukuSilentInstall.ShellOutcome.BinderDied,
        )
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        // Transport trouble never fails the attempt: the dialog tail owns it.
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(listOf("download", "verify", "stage", "shell", "canInstall", "commit"), host.events)
        // Staged copy cleaned; verified cache kept for the dialog commit.
        assertFalse(staged.exists())
        assertTrue(file.exists())
    }

    @Test
    fun `staging impossible falls back to the dialog path`() = runBlocking {
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = null,
        )
        val outcome = AppInstallFlow.runFlow(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(listOf("download", "verify", "stage", "canInstall", "commit"), host.events)
    }

    @Test
    fun `verify before stage is preserved — mismatch never stages`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            verifyResults = ArrayDeque(listOf(false)),
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
        )
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.HashMismatch, outcome)
        assertEquals(listOf("download", "verify"), host.events)
        assertFalse(file.exists())
        // Staged copy untouched (never created by the flow in this path).
        assertTrue(staged.exists())
    }

    @Test
    fun `cancel before staging never commits behind the user`() = runBlocking {
        val host = SilentFakeHost(
            cancelled = true,
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = stagedFile(),
        )
        val outcome = AppInstallFlow.runFlow(payload(), dest(), host)
        assertEquals(AppInstallFlow.Outcome.Cancelled, outcome)
        assertTrue(host.events.none { it == "stage" || it == "shell" })
        assertEquals(0, host.commitCalls)
    }

    @Test
    fun `pm failure keeps the cache with detail for retry`() = runBlocking {
        val staged = stagedFile()
        val raw = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: " +
            "Package com.example.app signatures do not match previously installed version]"
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            shellResult = ShizukuSilentInstall.ShellOutcome.Finished(1, raw),
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        val failed = outcome as AppInstallFlow.Outcome.SilentFailed
        assertEquals(raw, failed.detail)
        // Reuses the existing cause-aware mapping downstream.
        assertEquals(
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH,
            AppInstallResult.failureKindOf(failed.detail),
        )
        assertEquals(AppInstallFlow.TerminalKind.FAILED, with(AppInstallFlow) { outcome.terminalKind() })
        // Verified cache kept so Retry resumes without re-fetching.
        assertTrue(file.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun `silent installed maps to the success terminal`() {
        with(AppInstallFlow) {
            assertEquals(
                AppInstallFlow.TerminalKind.SUCCESS,
                AppInstallFlow.Outcome.SilentInstalled.terminalKind(),
            )
        }
    }

    @Test
    fun `resume re-verifies then goes silent without downloading`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            verifyResults = ArrayDeque(listOf(true)),
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
        )
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.SilentInstalled, outcome)
        assertEquals(0, host.downloadCalls)
        // Re-verify before reuse, then stage → shell.
        assertEquals(listOf("verify", "stage", "shell"), host.events)
        assertFalse(file.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun `resume with a stale cache re-downloads then goes silent`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            // First verify (cache reuse) fails; post-download verify passes.
            verifyResults = ArrayDeque(listOf(false, true)),
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
        )
        val file = dest()
        file.writeBytes(ByteArray(4))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.SilentInstalled, outcome)
        assertEquals(1, host.downloadCalls)
        assertEquals(listOf("verify", "download", "verify", "stage", "shell"), host.events)
    }

    @Test
    fun `resume falls back to dialog when silent degrades`() = runBlocking {
        val host = SilentFakeHost(
            verifyResults = ArrayDeque(listOf(true)),
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = stagedFile(),
            shellResult = ShizukuSilentInstall.ShellOutcome.BinderDied,
        )
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(0, host.downloadCalls)
        assertEquals(listOf("verify", "stage", "shell", "canInstall", "commit"), host.events)
    }
}
