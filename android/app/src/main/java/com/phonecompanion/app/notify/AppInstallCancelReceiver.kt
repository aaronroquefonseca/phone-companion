package com.phonecompanion.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Download Cancel action receiver (from the progress notification).
 *
 * Sets the [AppInstallCancels] flag the download loop observes, deletes
 * any partial file, and posts the single terminal "cancelled"
 * notification when it wins the [AppInstallTerminals] claim — the
 * activity observing the same cancel stays silent then, so exactly one
 * terminal state is ever user-visible. If the activity is gone (swiped
 * away), the receiver still cleans up and posts. Never throws.
 *
 * Logging discipline: package names and push ids only.
 */
class AppInstallCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pushId = intent.getStringExtra(AppInstallPayload.EXTRA_PUSH_ID).orEmpty()
        val packageName = intent.getStringExtra(AppInstallPayload.EXTRA_PACKAGE).orEmpty()
        if (pushId.isEmpty()) {
            Log.w(TAG, "app_install cancel missing push context; ignoring")
            return
        }
        AppInstallCancels.requestCancel(pushId)
        runCatching { AppInstallFiles.apkFile(app, pushId).delete() }
        val display = packageName.ifEmpty { pushId }
        if (AppInstallTerminals.claimTerminal(pushId)) {
            Log.w(TAG, "app_install download cancelled for $display")
            AppInstallNotifications.notifyDownloadCancelled(app, display, pushId)
        }
    }

    companion object {
        private const val TAG = "AppInstallCancel"
    }
}
