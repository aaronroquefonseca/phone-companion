package com.phonecompanion.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * PackageInstaller result receiver: the terminal state of the install
 * phase. Deletes the cached APK on every path and posts exactly one
 * user-visible notification (claimed via [AppInstallTerminals] so a
 * retried system broadcast can never double-post). No retry on any path —
 * user-cancelled is plain informational, failures carry the installer
 * status message.
 *
 * Logging discipline: package names only, never hashes/URLs/tokens.
 */
class AppInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pushId = intent.getStringExtra(AppInstallPayload.EXTRA_PUSH_ID).orEmpty()
        val packageName = intent.getStringExtra(AppInstallPayload.EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(AppInstallPayload.EXTRA_LABEL)
        if (pushId.isEmpty() || packageName.isEmpty()) {
            Log.w(TAG, "app_install result missing push context; ignoring")
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, AppInstallResult.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        // The verified APK is single-use: drop it whatever the outcome.
        runCatching { AppInstallFiles.apkFile(app, pushId).delete() }
        AppInstallCancels.clear(pushId)

        if (!AppInstallTerminals.claimTerminal(pushId)) return
        when (val outcome = AppInstallResult.classify(status, message)) {
            is AppInstallResult.Outcome.Installed -> {
                Log.w(TAG, "app_install installed $packageName")
                AppInstallNotifications.notifySuccess(app, label ?: packageName, pushId)
            }
            is AppInstallResult.Outcome.UserCancelled -> {
                Log.w(TAG, "app_install user-cancelled for $packageName")
                AppInstallNotifications.notifyUserCancelled(app, packageName, label, pushId)
            }
            is AppInstallResult.Outcome.Failed -> {
                Log.w(TAG, "app_install failed for $packageName")
                AppInstallNotifications.notifyFailed(app, packageName, label, pushId, outcome.statusMessage)
            }
        }
    }

    companion object {
        private const val TAG = "AppInstallResult"
    }
}
