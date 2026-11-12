package com.phonecompanion.app.notify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Receipt-triggered auto-download for `app_install` commands (FCM wake
 * intercept and poll converge here).
 *
 * On a fresh command the poll layer calls [begin]: the progress
 * notification posts immediately (the flow's first and only pre-terminal
 * post — no "tap to download" prompt), the foreground hub is offered the
 * command for an in-app dialog when foregrounded, and the full
 * download → verify → grant-gate → commit flow runs detached so the poll
 * worker is never held for a 100 MB transfer. Dismissal of the dialog
 * (or its absence in background) never touches the transfer:
 * cancellation stays with the progress Cancel action and the pre-install
 * UI, observed through [AppInstallSessions].
 *
 * The unknown-sources grant stays a pre-commit check: when it is missing
 * after verify, nothing commits — the verified APK stays cached and the
 * completion notification routes to the pre-install grant/onboarding
 * flow ([AppInstallNotifications.notifyGrant]), from which the Install
 * tap resumes without re-fetching. On a verified download with the grant
 * present the session commits immediately, so the system's own Install
 * confirmation is the user's single press.
 *
 * Logging discipline: package names and push ids only — never APK URLs,
 * hashes, tokens, or credentials.
 */
object AppInstallAutoDownload {

    private const val TAG = "AppInstallAuto"

    /** Detached owner: the transfer outlives the poll worker that started it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts the auto-download for one fresh [payload] under [attempt].
     * Never throws: launch failures (only from a dead VM, in practice
     * never) are logged shape-only.
     */
    fun begin(appContext: Context, payload: AppInstallPayload, attempt: String) {
        val app = appContext.applicationContext
        try {
            AppInstallNotifications.notifyProgress(app, payload, attempt, 0, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort first post: the flow still runs and later posts
            // replace-or-create the same id.
        }
        try {
            AppInstallEvents.emit(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Foreground surfacing is additive: never fail the download.
        }
        val dest = AppInstallFiles.apkFile(app, payload)
        val host = AutoHost(app, payload.pushId, attempt)
        try {
            scope.launch {
                val outcome = try {
                    AppInstallFlow.runFlow(
                        payload,
                        dest,
                        host,
                        onProgress = { read, total ->
                            runCatching {
                                AppInstallNotifications.notifyProgress(app, payload, attempt, read, total)
                            }
                        },
                        keepVerifiedOnRevoked = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Log.i(TAG, "auto download aborted for ${payload.packageName}")
                    return@launch
                }
                finishOutcome(app, payload, attempt, outcome)
            }
        } catch (_: Exception) {
            Log.i(TAG, "auto download launch failed for ${payload.packageName}")
        }
    }

    /** Single terminal exit: one outcome → (optional funnel post). */
    private fun finishOutcome(
        app: Context,
        payload: AppInstallPayload,
        attempt: String,
        outcome: AppInstallFlow.Outcome,
    ) {
        when (outcome) {
            is AppInstallFlow.Outcome.Committed -> {
                // Install phase terminal state belongs to the result
                // receiver (system confirmation → follow-up result).
            }
            is AppInstallFlow.Outcome.SilentInstalled -> {
                // Staged + cache copies are already deleted by the flow.
                AppInstallNotifications.notifyTerminal(
                    app,
                    AppInstallFlow.TerminalKind.SUCCESS,
                    AppInstallNotifications.TerminalTarget.from(payload),
                    attempt,
                )
            }
            is AppInstallFlow.Outcome.SilentFailed -> {
                // Raw pm output as detail: the existing cause-aware
                // mapping renders it (signature → App-info, else Retry
                // from the kept verified cache).
                AppInstallNotifications.notifyTerminal(
                    app,
                    AppInstallFlow.TerminalKind.FAILED,
                    AppInstallNotifications.TerminalTarget.from(payload),
                    attempt,
                    outcome.detail,
                    payload,
                )
            }
            is AppInstallFlow.Outcome.UnknownSourcesRevoked -> {
                // Verified APK stays cached; route to the one-time grant
                // flow instead of committing behind the user's back.
                AppInstallNotifications.notifyGrant(app, payload, attempt)
            }
            else -> {
                val kind = with(AppInstallFlow) { outcome.terminalKind() }
                if (kind != null) {
                    AppInstallNotifications.notifyTerminal(
                        app,
                        kind,
                        AppInstallNotifications.TerminalTarget.from(payload),
                        attempt,
                        payload = payload,
                    )
                }
            }
        }
    }

    /**
     * Android side of [AppInstallFlow.Host] for the detached auto path.
     * Same edges as the activity's host (downloader, SHA-256 verify,
     * grant check, installer commit); no UI callbacks — progress flows
     * through runFlow's onProgress into the notification.
     */
    private class AutoHost(
        private val app: Context,
        private val pushId: String,
        private val attempt: String,
    ) : AppInstallFlow.Host {
        override suspend fun download(
            payload: AppInstallPayload,
            dest: File,
            onProgress: (Long, Long?) -> Unit,
        ): AppInstallFlow.DownloadResult = withContext(Dispatchers.IO) {
            when (val r = AppInstallDownloader.download(payload, dest, onProgress)) {
                is AppInstallDownloader.Result.Done -> AppInstallFlow.DownloadResult.Done(r.bytes)
                is AppInstallDownloader.Result.TooLarge -> AppInstallFlow.DownloadResult.TooLarge
                is AppInstallDownloader.Result.Cancelled -> AppInstallFlow.DownloadResult.Cancelled
                is AppInstallDownloader.Result.Failed -> AppInstallFlow.DownloadResult.Failed
            }
        }

        override suspend fun verify(file: File, sha256: String): Boolean =
            withContext(Dispatchers.IO) {
                if (AppInstallSessions.isCancelled(pushId)) {
                    false
                } else {
                    AppInstallCrypto.verifyFile(file, sha256)
                }
            }

        override fun canInstall(): Boolean =
            app.packageManager.canRequestPackageInstalls()

        override fun isCancelled(): Boolean =
            AppInstallSessions.isCancelled(pushId)

        override suspend fun commit(payload: AppInstallPayload, apkFile: File): Boolean =
            withContext(Dispatchers.IO) {
                if (AppInstallSessions.isCancelled(payload.pushId)) return@withContext false
                AppInstallInstaller.commit(app, payload, apkFile, attempt)
            }

        /**
         * Silent branch (Shizuku): availability is probed at commit time —
         * a command arriving while backgrounded goes silent only when the
         * grant is already in place; permission is never requested here
         * (foreground-only). Staging copies the verified APK where the
         * shell UID can read it (app-private dirs are unreadable to
         * shell); the flow deletes the staged copy after every silent
         * terminal, and the 24 h purge bounds abandoned copies.
         */
        override fun silentStatus(): ShizukuSilentInstall.Status =
            ShizukuShell.statusNow()

        override suspend fun stageForShell(verifiedApk: File): File? =
            withContext(Dispatchers.IO) {
                try {
                    val staged = AppInstallFiles.stagedApkFile(app, pushId) ?: return@withContext null
                    staged.parentFile?.mkdirs()
                    verifiedApk.copyTo(staged, overwrite = true)
                    staged.takeIf { it.isFile }
                } catch (_: Exception) {
                    null
                }
            }

        override suspend fun shellCommit(stagedApk: File): ShizukuSilentInstall.ShellOutcome =
            ShizukuShell.installOnce(app, stagedApk.absolutePath)
    }
}
