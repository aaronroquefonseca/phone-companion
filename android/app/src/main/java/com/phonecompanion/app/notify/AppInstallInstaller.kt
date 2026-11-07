package com.phonecompanion.app.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

/**
 * APK install via [PackageInstaller.Session] (MODE_FULL_INSTALL).
 *
 * Never called without the unknown-sources grant (the flow re-checks
 * `packageManager.canRequestPackageInstalls()` after verify, before
 * commit) — from the receipt-triggered auto-download or the activity's
 * Install tap, always after download + SHA-256 verification. Streams the verified file into the session,
 * commits with a result [PendingIntent] to [AppInstallResultReceiver],
 * and returns: the terminal outcome is posted by the receiver (exactly
 * one notification), never here. Call off the main thread. Never throws —
 * a commit failure abandons the session, keeps the verified APK cached
 * (the failure notification's Retry resumes from it), and returns false
 * so the caller can post the error state. Logging discipline: package names only.
 */
object AppInstallInstaller {

    const val TAG = "AppInstallInstaller"
    const val ACTION_INSTALL_RESULT = "pc_app_install_result"

    /**
     * PendingIntent mutability for the installer-result intent. The
     * PackageInstaller fills the result extras (status, message) into our
     * intent via fillIn, which an IMMUTABLE PendingIntent would drop —
     * so API 31+ (where mutability must be declared) asks for MUTABLE.
     * Below 31 the flag does not exist and plain UPDATE_CURRENT keeps the
     * historical behavior. Pure ([sdkInt]-injected) so the branch is
     * unit-testable without Robolectric.
     */
    internal fun resultIntentFlags(sdkInt: Int): Int =
        if (sdkInt >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    fun commit(context: Context, payload: AppInstallPayload, apkFile: File, attempt: String?): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        params.setAppPackageName(payload.packageName)
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, -1).use { out ->
                    apkFile.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val resultIntent = Intent(context, AppInstallResultReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .putExtra(AppInstallPayload.EXTRA_PUSH_ID, payload.pushId)
                    .putExtra(AppInstallPayload.EXTRA_PACKAGE, payload.packageName)
                    .putExtra(AppInstallPayload.EXTRA_LABEL, payload.label)
                    .putExtra(AppInstallPayload.EXTRA_ATTEMPT, attempt)
                // Full payload extras (validated shapes, re-checked by the
                // receiver): the failure notification's Retry action needs
                // the pre-install tap target, which receivers otherwise
                // never see.
                for ((k, v) in payload.toExtras()) resultIntent.putExtra(k, v)
                val result = PendingIntent.getBroadcast(
                    context,
                    CompanionMessagingService.actionRequestCode(
                        payload.notifyId(),
                        ACTION_INSTALL_RESULT,
                    ),
                    resultIntent,
                    resultIntentFlags(android.os.Build.VERSION.SDK_INT),
                )
                session.commit(result.intentSender)
            }
            Log.i(TAG, "app_install committed session for ${payload.packageName}")
            return true
        } catch (e: Exception) {
            // Class name only — never paths, URLs, or hashes.
            Log.w(TAG, "app_install commit failed for ${payload.packageName} [${e.javaClass.simpleName}]")
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            // The verified APK stays cached: a commit-time failure
            // (installer unavailable, session abort, IO) is transient and
            // the failure notification's Retry resumes from it via the
            // runResume path (which re-verifies before reuse).
            return false
        }
    }
}
