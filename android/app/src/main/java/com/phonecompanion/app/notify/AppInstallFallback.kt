package com.phonecompanion.app.notify

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Last-resort fallback for a failed PackageInstaller session commit.
 *
 * Production behavior (verified on-device): on some devices the session
 * commit throws even though the APK is intact — the user saw "Install
 * could not start" with nothing installed, a dead end. Order per install
 * attempt stays Shizuku silent (when granted) → PackageInstaller commit →
 * this ACTION_VIEW fallback → terminal failure note: when the commit
 * reports [AppInstallFlow.Outcome.CommitFailed], the flow re-verifies the
 * cached APK and fires it at the system's default installer via VIEW, so
 * the OS package installer UI opens and the user taps Install there.
 *
 * The VIEW intent returns no result, so after firing it the funnel posts
 * an informational notification ("opened system installer — complete the
 * install there", see [AppInstallNotifications.notifyFallbackOpened]),
 * never the FAILED terminal. The command stays acked (it was acked at
 * download start — nothing here re-offers, nothing loops), and the cached
 * APK stays on disk until the 24 h stale purge: the user may complete the
 * install minutes later, and the file is the same verified bytes.
 *
 * Cause-aware mapping is untouched: a signature mismatch surfaces through
 * the installer-result receiver with its own App-info path (retrying the
 * same bytes via ANY path is futile). This fallback applies only to
 * transient commit failures, which carry no installer message at all.
 *
 * Pure JVM where possible: intent shape (action/type/flags), authority,
 * path scoping, the trigger gate, and the exactly-one-post disposition
 * are plain values, unit-testable without Robolectric. Only [fireFallback]
 * touches Android (FileProvider + startActivity).
 */
object AppInstallFallback {

    private const val TAG = "AppInstallFallback"

    /** MIME type handed to the system installer for the cached APK. */
    const val MIME_TYPE = "application/vnd.android.package-archive"

    /** Authority suffix: `<app package>.fileprovider` (see the manifest entry). */
    const val AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * FileProvider authorities for [appPackageName]. Must match the
     * manifest (`${applicationId}.fileprovider`) or `getUriForFile` throws
     * and the caller posts FAILED unchanged.
     */
    fun authorityFor(appPackageName: String): String = "$appPackageName$AUTHORITY_SUFFIX"

    /** VIEW: hand the verified APK to the system's default installer UI. */
    fun intentAction(): String = Intent.ACTION_VIEW

    /**
     * Read grant for the cross-uid installer + NEW_TASK (the funnel fires
     * from an application context). Pure (flag constants) so the shape is
     * unit-testable; mirrors [resultIntentFlags] mutability discipline in
     * [AppInstallInstaller].
     */
    fun intentFlags(): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

    /**
     * Scope gate: [apkFile] must be exactly this push's cached APK inside
     * the `app_push` cache dir — never an arbitrary path. The name check
     * runs first (the sanitized cache name for this push id), then the
     * canonical path must equal the expected entry, so `..` traversal and
     * sibling-dir lookalikes refuse. Pure file logic (dir-injected), so
     * the scoping is unit-testable.
     */
    fun isFallbackFile(cacheDir: File, payload: AppInstallPayload, apkFile: File): Boolean {
        if (apkFile.name != payload.cacheFileName()) return false
        return try {
            val expected = File(AppInstallFiles.pushDir(cacheDir), payload.cacheFileName()).canonicalFile
            apkFile.canonicalFile == expected && apkFile.isFile
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Trigger gate: the fallback fires exactly on a commit failure with an
     * intact verified file — never on success, cancel, download errors,
     * hash mismatch, user-cancelled, or a missing file. The signature path
     * never reaches here (it surfaces through the installer-result
     * receiver, not [AppInstallFlow.Outcome.CommitFailed]).
     */
    fun shouldAttemptFallback(
        outcome: AppInstallFlow.Outcome,
        verifiedFilePresent: Boolean,
    ): Boolean = outcome is AppInstallFlow.Outcome.CommitFailed && verifiedFilePresent

    /** Terminal disposition for a CommitFailed exit: exactly one post follows. */
    enum class CommitFailedDisposition {
        /** The VIEW intent started: post the informational fallback note. */
        OPENED_SYSTEM_INSTALLER,

        /** Fallback impossible (missing/corrupt file, no handler): the FAILED terminal. */
        POST_FAILED,
    }

    /**
     * Maps a CommitFailed exit to its single terminal post: the fallback
     * note only when a verified file was present AND the VIEW intent
     * started — every other combination posts the existing FAILED terminal
     * unchanged. The two arms share the per-attempt claim funnel, so even a
     * racing receiver stays silent.
     */
    fun dispositionForCommitFailed(
        verifiedFilePresent: Boolean,
        fallbackStarted: Boolean,
    ): CommitFailedDisposition =
        if (verifiedFilePresent && fallbackStarted) {
            CommitFailedDisposition.OPENED_SYSTEM_INSTALLER
        } else {
            CommitFailedDisposition.POST_FAILED
        }

    /**
     * Android edge: re-verify the cached APK, then fire the VIEW fallback.
     * Returns true when the system installer was opened. Returns false
     * (never throws, never a VIEW intent at a bad path) when the file is
     * missing/corrupt, the provider is misconfigured, or no handler
     * exists — the caller then posts the FAILED terminal unchanged. Never
     * deletes the cached APK either way.
     */
    fun fireFallback(appContext: Context, payload: AppInstallPayload, apkFile: File): Boolean {
        try {
            if (!isFallbackFile(appContext.cacheDir, payload, apkFile)) {
                Log.w(TAG, "app_install fallback refused: unexpected path for ${payload.packageName}")
                return false
            }
            if (!AppInstallCrypto.verifyFile(apkFile, payload.sha256)) {
                Log.w(TAG, "app_install fallback refused: re-verify failed for ${payload.packageName}")
                return false
            }
            val uri = FileProvider.getUriForFile(
                appContext,
                authorityFor(appContext.packageName),
                apkFile,
            )
            val view = Intent(intentAction())
                .setDataAndType(uri, MIME_TYPE)
                .addFlags(intentFlags())
            appContext.startActivity(view)
            Log.i(TAG, "app_install fallback opened system installer for ${payload.packageName}")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No VIEW handler (essentially impossible for apk VIEW), a
            // provider/paths misconfiguration, or a mid-flight delete: the
            // caller posts FAILED unchanged. Class name only, as elsewhere.
            Log.w(TAG, "app_install fallback could not start for ${payload.packageName}")
            return false
        }
    }

    /**
     * Single funnel for the CommitFailed exits of the auto-download flow
     * ([AppInstallAutoDownload]) and the pre-install ViewModel flow
     * ([com.phonecompanion.app.ui.AppInstallViewModel]): re-verify + fire
     * the VIEW fallback, then exactly one user-visible post — the
     * informational fallback note when the system installer opened, else
     * the existing FAILED terminal (with Retry, from the kept verified
     * cache) unchanged. Returns true when the system installer was opened.
     *
     * Call only from CommitFailed exits: any other outcome fails closed to
     * the FAILED terminal rather than opening the installer.
     */
    fun finishCommitFailed(
        appContext: Context,
        payload: AppInstallPayload,
        attempt: String?,
        outcome: AppInstallFlow.Outcome,
    ): Boolean {
        val apkFile = AppInstallFiles.apkFile(appContext, payload)
        val verifiedPresent = isFallbackFile(appContext.cacheDir, payload, apkFile) &&
            AppInstallCrypto.verifyFile(apkFile, payload.sha256)
        val started = shouldAttemptFallback(outcome, verifiedPresent) &&
            fireFallback(appContext, payload, apkFile)
        val target = AppInstallNotifications.TerminalTarget.from(payload)
        return when (dispositionForCommitFailed(verifiedPresent, started)) {
            CommitFailedDisposition.OPENED_SYSTEM_INSTALLER -> {
                AppInstallNotifications.notifyFallbackOpened(appContext, target, attempt)
                true
            }
            CommitFailedDisposition.POST_FAILED -> {
                AppInstallNotifications.notifyTerminal(
                    appContext,
                    AppInstallFlow.TerminalKind.FAILED,
                    target,
                    attempt,
                    null,
                    payload,
                )
                false
            }
        }
    }
}
