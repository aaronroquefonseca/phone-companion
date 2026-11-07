package com.phonecompanion.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Download Cancel action receiver (from the progress notification).
 *
 * Thin forwarder: sets the session cancel flag the download loop
 * observes, deletes any partial file, and funnels the single terminal
 * "cancelled" notification through [AppInstallNotifications.notifyTerminal]
 * — the activity observing the same cancel stays silent then, so exactly
 * one terminal state is ever user-visible. If the activity is gone
 * (swiped away), the receiver still cleans up and posts. Never throws.
 *
 * Logging discipline: package names and push ids only.
 */
class AppInstallCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pushId = intent.getStringExtra(AppInstallPayload.EXTRA_PUSH_ID).orEmpty()
        val packageName = intent.getStringExtra(AppInstallPayload.EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(AppInstallPayload.EXTRA_LABEL)
        val attempt = intent.getStringExtra(AppInstallPayload.EXTRA_ATTEMPT)
        if (pushId.isEmpty()) {
            Log.w(TAG, "app_install cancel missing push context; ignoring")
            return
        }
        AppInstallSessions.requestCancel(pushId)
        runCatching { AppInstallFiles.apkFile(app, pushId).delete() }
        val target = AppInstallNotifications.TerminalTarget(pushId, packageName.ifEmpty { pushId }, label)
        if (AppInstallNotifications.notifyTerminal(
                app,
                AppInstallFlow.TerminalKind.DOWNLOAD_CANCELLED,
                target,
                attempt,
            )
        ) {
            Log.i(TAG, "app_install download cancelled for ${target.packageName}")
        }
    }

    companion object {
        private const val TAG = "AppInstallCancel"
    }
}
