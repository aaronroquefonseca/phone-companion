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
 * Covers the Shizuku silent branch of [AppInstallFlow] (verify → stage →
 * version parse → privileged commit → version read-back): when it is
 * attempted, when it falls back to the dialog tail, and what each
 * terminal leaves on disk. The Android hosts (auto + activity) only
 * adapt calls; the ordering and fallback rules live here and are faked
 * without a device.
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

    /** Fake host with the silent branch scripted (status/stage/version/commit/await). */
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
        /** Null means the staged APK is unparseable → dialog fallback. */
        var stagedVersion: Long? = 42L,
        var commitOutcome: ShizukuSilentInstall.PrivilegedOutcome =
            ShizukuSilentInstall.PrivilegedOutcome.Committed(0),
        /** Last read-back result (null = package absent after grace). */
        var installedVersion: Long? = 42L,
        /** When true, privilegedCommit throws (binder vanished mid-flow). */
        var throwOnCommit: Boolean = false,
    ) : AppInstallFlow.Host {
        val events = mutableListOf<String>()
        var downloadCalls = 0
        var commitCalls = 0
        var committedStaged: File? = null
        var awaitExpected: Long? = null

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

        override suspend fun stagePrivileged(verifiedApk: File): File? {
            events += "stage"
            return staged
        }

        override suspend fun stagedVersionCode(stagedApk: File): Long? {
            events += "version"
            return stagedVersion
        }

        override suspend fun privilegedCommit(
            stagedApk: File,
            packageName: String,
        ): ShizukuSilentInstall.PrivilegedOutcome {
            events += "silent"
            if (throwOnCommit) throw IllegalStateException("binder vanished")
            committedStaged = stagedApk
            return commitOutcome
        }

        override suspend fun awaitInstalledVersion(
            packageName: String,
            expectedVersionCode: Long,
        ): Long? {
            events += "await"
            awaitExpected = expectedVersionCode
            return installedVersion
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
        // Verify-before-stage ordering, then parse → commit → read-back;
        // the dialog tail (grant re-check + PackageInstaller commit) is
        // never touched.
        assertEquals(listOf("download", "verify", "stage", "version", "silent", "await"), host.events)
        assertEquals(staged, host.committedStaged)
        // Parse-once value passes through to the read-back.
        assertEquals(42L, host.awaitExpected)
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
            commitOutcome = ShizukuSilentInstall.PrivilegedOutcome.TransportDied,
        )
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        // Transport trouble never fails the attempt: the dialog tail owns it.
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(listOf("download", "verify", "stage", "version", "silent", "canInstall", "commit"), host.events)
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
    fun `unparseable staged apk fails closed to the dialog path`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            stagedVersion = null,
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        // Unknown state: never a silent success, never a failure — the
        // dialog tail decides from the cached verified APK.
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(listOf("download", "verify", "stage", "version", "canInstall", "commit"), host.events)
        assertFalse(staged.exists())
        assertTrue(file.exists())
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
        assertTrue(host.events.none { it == "stage" || it == "silent" })
        assertEquals(0, host.commitCalls)
    }

    @Test
    fun `version mismatch falls back to the dialog chain, never failed`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            stagedVersion = 42L,
            commitOutcome = ShizukuSilentInstall.PrivilegedOutcome.Committed(0),
            // A different build is installed: the silent attempt is
            // unverified even though the session reported success.
            installedVersion = 41L,
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        // Fail over to the existing chain (dialog commit → ACTION_VIEW →
        // terminal), exactly as transport trouble does — never a silent
        // failure terminal, never a retry loop.
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(
            listOf("download", "verify", "stage", "version", "silent", "await", "canInstall", "commit"),
            host.events,
        )
        assertEquals(42L, host.awaitExpected)
        // Staged copy cleaned; verified cache kept for the dialog commit.
        assertFalse(staged.exists())
        assertTrue(file.exists())
    }

    @Test
    fun `absent package after grace falls back to the dialog chain`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            commitOutcome = ShizukuSilentInstall.PrivilegedOutcome.Committed(0),
            installedVersion = null,
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertTrue(host.events.contains("await"))
        assertEquals(1, host.commitCalls)
        assertFalse(staged.exists())
        assertTrue(file.exists())
    }

    @Test
    fun `unknown commit status still verifies via read-back`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            // No status arrived in time: unknown — the read-back decides.
            commitOutcome = ShizukuSilentInstall.PrivilegedOutcome.Committed(null),
            installedVersion = 42L,
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.SilentInstalled, outcome)
        assertFalse(file.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun `failure status without matching read-back falls back`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            commitOutcome = ShizukuSilentInstall.PrivilegedOutcome.Committed(1),
            installedVersion = null,
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        // The status alone never fails the attempt: unverified means the
        // dialog tail decides (it re-derives signature vs transient).
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(1, host.commitCalls)
        assertTrue(file.exists())
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
        // Re-verify before reuse, then stage → version → commit → await.
        assertEquals(listOf("verify", "stage", "version", "silent", "await"), host.events)
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
        assertEquals(listOf("verify", "download", "verify", "stage", "version", "silent", "await"), host.events)
    }

    @Test
    fun `resume falls back to dialog when silent degrades`() = runBlocking {
        val host = SilentFakeHost(
            verifyResults = ArrayDeque(listOf(true)),
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = stagedFile(),
            commitOutcome = ShizukuSilentInstall.PrivilegedOutcome.TransportDied,
        )
        val file = dest()
        file.writeBytes(ByteArray(10))
        val outcome = AppInstallFlow.runResume(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertEquals(0, host.downloadCalls)
        assertEquals(listOf("verify", "stage", "version", "silent", "canInstall", "commit"), host.events)
    }

    @Test
    fun `privileged host exceptions fail closed to the dialog path`() = runBlocking {
        val staged = stagedFile()
        val host = SilentFakeHost(
            status = ShizukuSilentInstall.Status.AVAILABLE_GRANTED,
            staged = staged,
            throwOnCommit = true,
        )
        val file = dest()
        val outcome = AppInstallFlow.runFlow(payload(), file, host)
        assertEquals(AppInstallFlow.Outcome.Committed, outcome)
        assertNull(host.awaitExpected)
        assertTrue(file.exists())
    }
}
