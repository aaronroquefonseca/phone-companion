package com.phonecompanion.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.phonecompanion.app.R
import com.phonecompanion.app.notify.AppInstallCrypto
import com.phonecompanion.app.notify.AppInstallDownloader
import com.phonecompanion.app.notify.AppInstallFiles
import com.phonecompanion.app.notify.AppInstallFlow
import com.phonecompanion.app.notify.AppInstallInstaller
import com.phonecompanion.app.notify.AppInstallNotifications
import com.phonecompanion.app.notify.AppInstallPayload
import com.phonecompanion.app.notify.AppInstallSessions
import com.phonecompanion.app.notify.ShizukuShell
import com.phonecompanion.app.notify.ShizukuSilentInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Retains the remote APK install transfer across configuration changes.
 *
 * The download/verify/commit coroutine lives here (not in the Activity),
 * so rotation neither cancels the transfer nor spawns a second flow: the
 * recreated Activity just re-collects [uiState]. Back-press / explicit
 * finish cancels via [cancel] (which only sets the session flag — the
 * flow observes it and funnels its single terminal state).
 *
 * Single terminal exit: [AppInstallFlow.runResume] returns one [Outcome]
 * and [finishOutcome] maps it once to (optional notification via the
 * per-attempt funnel + in-app state). No payload/downloading/committed
 * flag agreement, no TTL-map reasoning in the UI layer.
 */
class AppInstallViewModel(app: Application) : AndroidViewModel(app) {

    /** In-app install states. Exactly one terminal state per attempt. */
    sealed interface InstallUiState {
        /** Pre-install consent screen. */
        data object Ready : InstallUiState

        /** Download in flight; percent null when the length is unknown. */
        data class Downloading(val percent: Int?) : InstallUiState

        /** SHA-256 verification running. */
        data object Verifying : InstallUiState

        /** Installer commit attempted; the system dialog owns the UI now. */
        data object Installing : InstallUiState

        /** Silent commit via Shizuku running; no system dialog appears. */
        data object SilentInstalling : InstallUiState

        /** Single terminal exit: in-app text (the notification, if any, posted). */
        data class Finished(val message: String) : InstallUiState

        /** Commit handed to the installer: the result receiver owns the terminal. */
        data object HandedToInstaller : InstallUiState
    }

    private val _uiState = MutableStateFlow<InstallUiState>(InstallUiState.Ready)
    val uiState: StateFlow<InstallUiState> = _uiState

    private var currentPushId: String? = null
    private var activeJob: Job? = null

    /** Owned scope (not viewModelScope): survives rotation, dies in onCleared. */
    private val flowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val isFlowActive: Boolean get() = activeJob?.isActive == true

    /**
     * Starts the install flow for one explicit Install tap. Re-checks the
     * unknown-sources grant at tap time (it could have been revoked while
     * the screen was open) — UNLESS Shizuku silent install is granted, in
     * which case no grant is needed (shell `pm install` needs none, like
     * `adb install`) and the tap proceeds silently. Mints a fresh attempt
     * nonce — never reuses a previous attempt, so a retry always claims
     * fresh. Runs the resume entry: a verified APK left behind by a
     * grant-gated auto-download commits without re-fetching; otherwise the
     * full download runs.
     */
    fun start(payload: AppInstallPayload) {
        if (isFlowActive) return
        val app = getApplication<Application>()
        val shizukuSilent = ShizukuShell.statusNow() == ShizukuSilentInstall.Status.AVAILABLE_GRANTED
        if (!app.packageManager.canRequestPackageInstalls() && !shizukuSilent) return
        val attempt = AppInstallSessions.startAttempt(payload.pushId)
        currentPushId = payload.pushId
        val dest = AppInstallFiles.apkFile(app, payload)
        _uiState.value = InstallUiState.Downloading(0)
        val host = RealHost(app, payload.pushId, attempt) { phase ->
            _uiState.value = when (phase) {
                Phase.VERIFYING -> InstallUiState.Verifying
                Phase.INSTALLING -> InstallUiState.Installing
                Phase.SILENT_INSTALLING -> InstallUiState.SilentInstalling
            }
        }
        activeJob = flowScope.launch {
            val outcome = try {
                AppInstallFlow.runResume(payload, dest, host) { read, total ->
                    val pct = total?.takeIf { it > 0 }?.let {
                        ((read * 100) / it).toInt().coerceIn(0, 100)
                    }
                    _uiState.value = InstallUiState.Downloading(pct)
                    AppInstallNotifications.notifyProgress(app, payload, attempt, read, total)
                }
            } catch (e: CancellationException) {
                // Scope died mid-flow (VM cleared): still close the attempt
                // exactly once through the funnel — never silently.
                AppInstallFlow.Outcome.Cancelled
            }
            finishOutcome(payload, attempt, outcome)
        }
    }

    /**
     * Requests cancellation. Flag-only: the flow observes it (mid-copy
     * and at phase boundaries) and funnels its single terminal state —
     * the job itself is left to terminate through the funnel so the
     * attempt still ends exactly once. No-op when no flow is active (the
     * funnel claim is already spent or no tap happened yet).
     */
    fun cancel() {
        val pushId = currentPushId ?: return
        if (!isFlowActive) return
        AppInstallSessions.requestCancel(pushId)
    }

    override fun onCleared() {
        flowScope.cancel()
        super.onCleared()
    }

    /** Single terminal exit: one outcome → (optional funnel post + in-app state). */
    private fun finishOutcome(
        payload: AppInstallPayload,
        attempt: String?,
        outcome: AppInstallFlow.Outcome,
    ) {
        activeJob = null
        if (outcome is AppInstallFlow.Outcome.Committed) {
            // Install phase terminal state belongs to the result receiver.
            _uiState.value = InstallUiState.HandedToInstaller
            return
        }
        val kind = with(AppInstallFlow) { outcome.terminalKind() }
        if (kind != null) {
            val detail = (outcome as? AppInstallFlow.Outcome.SilentFailed)?.detail
            AppInstallNotifications.notifyTerminal(
                getApplication(),
                kind,
                AppInstallNotifications.TerminalTarget.from(payload),
                attempt,
                detail,
                // Retry target for FAILED posts (silent or dialog): the
                // pre-install UI, whose Install tap resumes via runResume
                // (re-verified cache, no re-download when intact).
                payload.takeIf { kind == AppInstallFlow.TerminalKind.FAILED },
            )
        }
        _uiState.value = InstallUiState.Finished(messageFor(payload, outcome))
    }

    private fun messageFor(payload: AppInstallPayload, outcome: AppInstallFlow.Outcome): String {
        val app = getApplication<Application>()
        return when (outcome) {
            is AppInstallFlow.Outcome.TooLarge ->
                app.getString(R.string.app_install_error_too_large)
            is AppInstallFlow.Outcome.Cancelled ->
                app.getString(R.string.app_install_error_cancelled)
            is AppInstallFlow.Outcome.DownloadFailed ->
                app.getString(R.string.app_install_error_download)
            is AppInstallFlow.Outcome.HashMismatch ->
                app.getString(R.string.app_install_error_mismatch)
            is AppInstallFlow.Outcome.CommitFailed ->
                app.getString(R.string.app_install_error_install)
            is AppInstallFlow.Outcome.SilentInstalled ->
                app.getString(R.string.app_install_silent_done, payload.label)
            is AppInstallFlow.Outcome.SilentFailed ->
                app.getString(R.string.app_install_error_install)
            is AppInstallFlow.Outcome.UnknownSourcesRevoked ->
                app.getString(R.string.app_install_error_unknown_sources)
            is AppInstallFlow.Outcome.Committed ->
                app.getString(R.string.app_install_installing)
        }
    }

    private enum class Phase { VERIFYING, INSTALLING, SILENT_INSTALLING }

    /**
     * Android side of [AppInstallFlow.Host]. Reports phase transitions
     * for the Verifying/Installing states; checks the cancel flag at
     * phase boundaries so a cancel during verify/install still lands
     * Cancelled instead of committing behind the user's back.
     */
    private class RealHost(
        private val app: Application,
        private val pushId: String,
        private val attempt: String,
        private val onPhase: (Phase) -> Unit,
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
                    onPhase(Phase.VERIFYING)
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
                onPhase(Phase.INSTALLING)
                AppInstallInstaller.commit(app, payload, apkFile, attempt)
            }

        /**
         * Silent branch (Shizuku): the Install tap goes silent only when
         * the grant is already in place (checked in [start]); this probe
         * re-checks at commit time so a binder dying mid-download falls
         * back to the dialog tail instead of failing.
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

        override suspend fun shellCommit(stagedApk: File): ShizukuSilentInstall.ShellOutcome {
            onPhase(Phase.SILENT_INSTALLING)
            return ShizukuShell.installOnce(app, stagedApk.absolutePath)
        }
    }
}
