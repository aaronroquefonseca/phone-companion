package com.phonecompanion.app.notify

import java.io.File

/**
 * The remote APK install flow as linear suspend functions:
 * download → verify → unknown-sources re-check → commit.
 *
 * When the host reports Shizuku granted, the post-verify tail commits
 * silently instead (verify → stage → shell `pm install`, no grant gate —
 * shell installs need none, like `adb install`): success and pm-failure
 * are terminal here; transport edges (staging impossible, binder died)
 * fall back to the dialog tail from the cached verified APK, never a
 * failure. Dialog path is untouched when Shizuku is absent or denied.
 *
 * Two entries: [runFlow] downloads unconditionally (poll-triggered
 * auto-download and fresh Install taps); [runResume] reuses an already
 * verified cached APK when one waits (grant-gated auto-download left it
 * behind) and only re-downloads on a miss. The two receivers are thin
 * forwarders (cancel flag + terminal funnel) that never reimplement
 * these steps. Outcomes are exhaustive ([Outcome]) so callers collapse
 * the old near-identical claim-notify-set-text arms into a single
 * terminal exit driven by [Outcome.terminalKind].
 *
 * Pure JVM (zero Android imports): the host side (network, PackageManager,
 * PackageInstaller, files) is injected via [Host], so the full order —
 * including the post-verify unknown-sources re-check — is unit-testable
 * with a fake host. Never retries: every outcome is terminal.
 */
object AppInstallFlow {

    /** Terminal notification funnel for a flow outcome (null = none posted). */
    enum class TerminalKind {
        SUCCESS,
        USER_CANCELLED,
        FAILED,
        HASH_MISMATCH,
        DOWNLOAD_FAILED,
        TOO_LARGE,
        DOWNLOAD_CANCELLED,
    }

    /** Android side of the flow (network, PackageManager, installer, files). */
    interface Host {
        /** Bounded HTTPS download; deletes partials on any non-Done path. */
        suspend fun download(
            payload: AppInstallPayload,
            dest: File,
            onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
        ): DownloadResult

        /** SHA-256 check of the complete file. */
        suspend fun verify(file: File, sha256: String): Boolean

        /** Unknown-sources grant (`PackageManager.canRequestPackageInstalls`). */
        fun canInstall(): Boolean

        /** True when the user cancelled this attempt (phase-boundary checks). */
        fun isCancelled(): Boolean

        /**
         * PackageInstaller commit. Returns true once the session commits
         * (the installer-result receiver owns the terminal from there);
         * deletes the cached APK and returns false when commit fails.
         */
        suspend fun commit(payload: AppInstallPayload, apkFile: File): Boolean

        /**
         * Silent-install availability for this attempt. Default
         * UNAVAILABLE keeps the dialog path untouched for hosts that do
         * not implement Shizuku (and all pre-existing fake hosts).
         */
        fun silentStatus(): ShizukuSilentInstall.Status =
            ShizukuSilentInstall.Status.UNAVAILABLE

        /**
         * Copies the verified APK where the shell UID can read it (the
         * shell cannot read app-private dirs). Returns the staged file, or
         * null when staging is impossible — the caller falls back to the
         * dialog flow, never a failure. Called only AFTER a passed verify,
         * and only on the silent path.
         */
        suspend fun stageForShell(verifiedApk: File): File? = null

        /**
         * One shell `pm install` of the staged APK. Any transport edge
         * surfaces as [ShizukuSilentInstall.ShellOutcome.BinderDied] so the
         * caller falls back to the dialog flow — never a terminal failure.
         */
        suspend fun shellCommit(stagedApk: File): ShizukuSilentInstall.ShellOutcome =
            ShizukuSilentInstall.ShellOutcome.BinderDied
    }

    /** Bounded download outcome (mirrors the downloader, host-agnostic). */
    sealed interface DownloadResult {
        data class Done(val bytes: Long) : DownloadResult
        data object TooLarge : DownloadResult
        data object Cancelled : DownloadResult
        data object Failed : DownloadResult
    }

    /** Terminal outcome of one Install-tap attempt. Exactly one per run. */
    sealed interface Outcome {
        /** Session committed: the installer-result receiver owns the terminal. */
        data object Committed : Outcome

        /** Server-declared or actual size exceeded the 100 MB cap. */
        data object TooLarge : Outcome

        /** Cancel observed mid-copy (flag or explicit cancel). */
        data object Cancelled : Outcome

        /** Network/IO failure. */
        data object DownloadFailed : Outcome

        /** SHA-256 mismatch: file deleted, hard refuse, no commit. */
        data object HashMismatch : Outcome

        /**
         * PackageInstaller commit failed: the verified APK is KEPT cached
         * so the failure notification's Retry can resume from it via
         * [runResume] (which re-verifies before reuse) instead of
         * re-downloading. A commit-time failure (installer unavailable,
         * session abort, IO) says nothing about the bytes — unlike a hash
         * mismatch, the file was proven good.
         */
        data object CommitFailed : Outcome

        /**
         * Unknown-sources grant gone after verify: render the onboarding
         * gate and stop — fail closed, no notification, no commit. The
         * verified APK is deleted so no installable artifact lingers,
         * unless the caller keeps it for grant-resume
         * ([runFlow] with keepVerifiedOnRevoked, [runResume]): then the
         * file stays cached and the caller routes the user through the
         * one-time grant flow instead.
         */
        data object UnknownSourcesRevoked : Outcome

        /**
         * Silent commit succeeded (Shizuku shell `pm install` exit 0):
         * terminal — the caller posts SUCCESS. The staged copy and the
         * cache copy are already deleted.
         */
        data object SilentInstalled : Outcome

        /**
         * Silent commit ran but pm refused (nonzero exit): terminal — the
         * caller posts FAILED with [detail] (raw pm output, capped), which
         * the existing cause-aware mapping renders (signature mismatch →
         * App-info, otherwise Retry). The staged copy is deleted; the
         * verified cache is KEPT so Retry resumes via [runResume]
         * (which re-verifies before reuse) without re-fetching.
         */
        data class SilentFailed(val detail: String?) : Outcome
    }

    /**
     * Runs download → verify → unknown-sources re-check → commit exactly
     * once. Never throws for flow failures (they are [Outcome]s); an
     * unexpected host exception fails closed to [Outcome.DownloadFailed]
     * (or [Outcome.CommitFailed] past verify). Partials from a failed
     * download and mismatched files are deleted; a verified APK survives
     * a commit failure so Retry can resume without re-fetching.
     *
     * @param keepVerifiedOnRevoked when true, a post-verify grant refusal
     * keeps the verified APK cached ([Outcome.UnknownSourcesRevoked] all
     * the same) so a grant-gated auto-download can resume after the
     * one-time grant instead of re-fetching. Default false preserves the
     * delete-on-revoke behavior for explicit-tap flows.
     */
    suspend fun runFlow(
        payload: AppInstallPayload,
        dest: File,
        host: Host,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        keepVerifiedOnRevoked: Boolean = false,
    ): Outcome {
        val downloaded = try {
            host.download(payload, dest, onProgress)
        } catch (_: Exception) {
            runCatching { dest.delete() }
            return Outcome.DownloadFailed
        }
        when (downloaded) {
            is DownloadResult.TooLarge -> return Outcome.TooLarge
            is DownloadResult.Cancelled -> return Outcome.Cancelled
            is DownloadResult.Failed -> return Outcome.DownloadFailed
            is DownloadResult.Done -> { /* fall through to verify */ }
        }
        val match = try {
            host.verify(dest, payload.sha256)
        } catch (_: Exception) {
            false
        }
        if (!match) {
            runCatching { dest.delete() }
            // A cancel landing mid-verify surfaces as a verify miss —
            // report it as the cancellation it was, not a mismatch.
            return if (host.isCancelled()) Outcome.Cancelled else Outcome.HashMismatch
        }
        // Re-check AFTER verify, BEFORE commit: the grant could have been
        // revoked while the (long) download ran. Fail closed.
        // Silent path first: when Shizuku is granted, the verified bytes
        // commit via the shell service — shell `pm install` needs no
        // unknown-sources grant (like `adb install`), so the grant gate is
        // skipped ONLY here. Any transport edge (staging impossible,
        // binder died) falls through to the dialog tail below from the
        // cached verified APK — never a failure.
        if (host.silentStatus() == ShizukuSilentInstall.Status.AVAILABLE_GRANTED) {
            runSilentCommit(dest, host)?.let { return it }
        }
        if (!host.canInstall()) {
            if (!keepVerifiedOnRevoked) runCatching { dest.delete() }
            return Outcome.UnknownSourcesRevoked
        }
        return commitTail(payload, dest, host)
    }

    /**
     * Resume entry for the pre-install UI after a grant-gated
     * auto-download: when [dest] already holds this push's verified APK,
     * skip the download and go straight to the pre-commit gate → commit
     * tail (the user's single tap is then the system's own Install
     * confirmation). On any cache miss (absent file, failed re-verify,
     * cancellation) it falls back to the full [runFlow] (re-download).
     * A grant refusal here keeps the cache — the user re-grants and
     * retries without re-fetching.
     */
    suspend fun runResume(
        payload: AppInstallPayload,
        dest: File,
        host: Host,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Outcome {
        if (host.isCancelled()) return Outcome.Cancelled
        val reuse = try {
            dest.isFile && host.verify(dest, payload.sha256)
        } catch (_: Exception) {
            false
        }
        if (!reuse) return runFlow(payload, dest, host, onProgress)
        if (host.isCancelled()) return Outcome.Cancelled
        // Retry of a silent-capable attempt re-enters the silent branch
        // from the re-verified cache (stage → shell, no re-download);
        // transport edges fall through to the dialog tail below.
        if (host.silentStatus() == ShizukuSilentInstall.Status.AVAILABLE_GRANTED) {
            runSilentCommit(dest, host)?.let { return it }
        }
        if (!host.canInstall()) return Outcome.UnknownSourcesRevoked
        return commitTail(payload, dest, host)
    }

    /**
     * Silent commit tail: stage the verified APK where shell can read it,
     * run one shell `pm install`, then exactly one outcome. Returns null
     * when the silent attempt is impossible (staging failed, binder died
     * mid-flow, or cancelled before staging) so the caller falls through
     * to the dialog tail from the cached verified APK. A cancel landing
     * here never commits behind the user's back — it reports Cancelled.
     *
     * Cleanup: the staged copy is always deleted (success, pm failure, or
     * fallback); the cache copy follows the existing rules (deleted on
     * silent success, kept on pm failure for Retry, kept on fallback for
     * the dialog tail).
     */
    private suspend fun runSilentCommit(
        dest: File,
        host: Host,
    ): Outcome? {
        if (host.isCancelled()) return Outcome.Cancelled
        val staged = try {
            host.stageForShell(dest)
        } catch (_: Exception) {
            null
        }
        if (staged == null) return null
        return when (val r = try {
            host.shellCommit(staged)
        } catch (_: Exception) {
            ShizukuSilentInstall.ShellOutcome.BinderDied
        }) {
            is ShizukuSilentInstall.ShellOutcome.Finished -> {
                if (r.exitCode == 0) {
                    runCatching { staged.delete() }
                    runCatching { dest.delete() }
                    Outcome.SilentInstalled
                } else {
                    runCatching { staged.delete() }
                    Outcome.SilentFailed(r.output.ifBlank { null })
                }
            }
            is ShizukuSilentInstall.ShellOutcome.BinderDied -> {
                runCatching { staged.delete() }
                null
            }
        }
    }

    /**
     * Shared commit tail: PackageInstaller commit, then exactly one
     * outcome. A commit failure keeps the cached APK (it was just
     * verified — the failure is environmental, not the bytes): the
     * failure notification's Retry resumes from it without re-fetching.
     * A cancellation landing mid-commit reports Cancelled instead of a
     * commit failure.
     */
    private suspend fun commitTail(
        payload: AppInstallPayload,
        dest: File,
        host: Host,
    ): Outcome {
        val committed = try {
            host.commit(payload, dest)
        } catch (_: Exception) {
            return Outcome.CommitFailed
        }
        return if (committed) {
            Outcome.Committed
        } else {
            // A cancel landing mid-commit aborts the commit — report the
            // cancellation, not a commit failure.
            if (host.isCancelled()) Outcome.Cancelled else Outcome.CommitFailed
        }
    }

    /**
     * Single terminal-funnel mapping: which notification (if any) an
     * [Outcome] posts. Committed belongs to the installer-result
     * receiver; UnknownSourcesRevoked renders the in-app gate instead.
     */
    fun Outcome.terminalKind(): TerminalKind? = when (this) {
        is Outcome.Committed -> null
        is Outcome.TooLarge -> TerminalKind.TOO_LARGE
        is Outcome.Cancelled -> TerminalKind.DOWNLOAD_CANCELLED
        is Outcome.DownloadFailed -> TerminalKind.DOWNLOAD_FAILED
        is Outcome.HashMismatch -> TerminalKind.HASH_MISMATCH
        is Outcome.CommitFailed -> TerminalKind.FAILED
        is Outcome.SilentInstalled -> TerminalKind.SUCCESS
        is Outcome.SilentFailed -> TerminalKind.FAILED
        is Outcome.UnknownSourcesRevoked -> null
    }
}
