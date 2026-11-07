package com.phonecompanion.app.notify

import java.io.File

/**
 * The remote APK install flow as ONE linear suspend function:
 * download → verify → unknown-sources re-check → commit.
 *
 * The ViewModel calls [runFlow] once per explicit Install tap; the two
 * receivers are thin forwarders (cancel flag + terminal funnel) that never
 * reimplement these steps. Outcomes are exhaustive ([Outcome]) so the
 * caller collapses the old near-identical claim-notify-set-text arms into
 * a single terminal exit driven by [Outcome.terminalKind].
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

        /** PackageInstaller commit failed: file deleted. */
        data object CommitFailed : Outcome

        /**
         * Unknown-sources grant gone after verify: render the onboarding
         * gate and stop — fail closed, no notification, no commit. The
         * verified APK is deleted so no installable artifact lingers.
         */
        data object UnknownSourcesRevoked : Outcome
    }

    /**
     * Runs download → verify → unknown-sources re-check → commit exactly
     * once. Never throws for flow failures (they are [Outcome]s); an
     * unexpected host exception fails closed to [Outcome.DownloadFailed]
     * (or [Outcome.CommitFailed] past verify) with the partial deleted.
     */
    suspend fun runFlow(
        payload: AppInstallPayload,
        dest: File,
        host: Host,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
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
        if (!host.canInstall()) {
            runCatching { dest.delete() }
            return Outcome.UnknownSourcesRevoked
        }
        val committed = try {
            host.commit(payload, dest)
        } catch (_: Exception) {
            runCatching { dest.delete() }
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
        is Outcome.UnknownSourcesRevoked -> null
    }
}
