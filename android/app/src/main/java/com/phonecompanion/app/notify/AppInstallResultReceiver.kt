package com.phonecompanion.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * PackageInstaller result receiver: the terminal state of the install
 * phase. Thin forwarder: classifies the status and funnels the single
 * user-visible notification through [AppInstallNotifications.notifyTerminal]
 * (claimed per attempt, so a retried system broadcast can never
 * double-post). Success and user-cancelled delete the cached APK;
 * failures KEEP it — transient failures Retry from the cache via the
 * runResume path (which re-verifies before reuse), and a
 * signature-mismatch keeps it so uninstall-then-fresh-install needs no
 * re-download. The one exception is `STATUS_PENDING_USER_ACTION`: the
 * system's confirmation intent is launched with the cached APK kept and
 * no terminal claimed — the follow-up result owns the terminal. Failure
 * notifications are cause-aware (App-info for signature mismatch, Retry
 * otherwise); user-cancelled stays plain informational with no retry.
 *
 * Logging discipline: package names only, never hashes/URLs/tokens.
 */
class AppInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pushId = intent.getStringExtra(AppInstallPayload.EXTRA_PUSH_ID).orEmpty()
        val packageName = intent.getStringExtra(AppInstallPayload.EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(AppInstallPayload.EXTRA_LABEL)
        val attempt = intent.getStringExtra(AppInstallPayload.EXTRA_ATTEMPT)
        if (pushId.isEmpty() || packageName.isEmpty()) {
            Log.w(TAG, "app_install result missing push context; ignoring")
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, AppInstallResult.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        // The system wants the user to confirm first: launch its
        // confirmation intent (carried under Intent.EXTRA_INTENT) and
        // return WITHOUT deleting the cached APK and WITHOUT claiming a
        // terminal — the follow-up result re-enters this receiver and
        // owns the terminal then.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            launchConfirmation(app, intent, packageName)
            return
        }

        val target = AppInstallNotifications.TerminalTarget(pushId, packageName, label)
        when (val outcome = AppInstallResult.classify(status, message)) {
            is AppInstallResult.Outcome.Installed -> {
                runCatching { AppInstallFiles.apkFile(app, pushId).delete() }
                if (notify(app, AppInstallFlow.TerminalKind.SUCCESS, target, attempt)) {
                    Log.i(TAG, "app_install installed $packageName")
                }
            }
            is AppInstallResult.Outcome.UserCancelled -> {
                runCatching { AppInstallFiles.apkFile(app, pushId).delete() }
                if (notify(app, AppInstallFlow.TerminalKind.USER_CANCELLED, target, attempt)) {
                    Log.i(TAG, "app_install user-cancelled for $packageName")
                }
            }
            is AppInstallResult.Outcome.PendingUserAction -> {
                // Unreachable: STATUS_PENDING_USER_ACTION returns above
                // before classification. Fail silent, never terminal.
                Log.w(TAG, "app_install unexpected pending state for $packageName")
            }
            is AppInstallResult.Outcome.Failed -> {
                // Keep the verified APK (see class kdoc): transient
                // failures Retry from it, signature failures reinstall
                // fresh from it after the user uninstalls — runResume
                // re-verifies before any reuse, and the stale-cache purge
                // bounds leftovers.
                val retryPayload = payloadFrom(intent)
                if (notify(
                        app,
                        AppInstallFlow.TerminalKind.FAILED,
                        target,
                        attempt,
                        outcome.statusMessage,
                        retryPayload,
                    )
                ) {
                    Log.w(TAG, "app_install failed for $packageName")
                }
            }
        }
    }

    /**
     * Launches the system's install-confirmation activity (carried under
     * [Intent.EXTRA_INTENT] on `STATUS_PENDING_USER_ACTION`). The cached
     * APK stays on disk for the follow-up result; no terminal is claimed.
     * Never throws.
     */
    private fun launchConfirmation(app: Context, intent: Intent, packageName: String) {
        try {
            @Suppress("DEPRECATION")
            val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm == null) {
                Log.w(TAG, "app_install pending action without confirmation intent for $packageName")
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(confirm)
            Log.i(TAG, "app_install launched confirmation for $packageName")
        } catch (_: Exception) {
            Log.w(TAG, "app_install confirmation launch failed for $packageName")
        }
    }

    private fun notify(
        context: Context,
        kind: AppInstallFlow.TerminalKind,
        target: AppInstallNotifications.TerminalTarget,
        attempt: String?,
        detail: String? = null,
        payload: AppInstallPayload? = null,
    ): Boolean = AppInstallNotifications.notifyTerminal(context, kind, target, attempt, detail, payload)

    /**
     * Rebuilds the validated payload from the installer-result extras so
     * the failure notification's Retry action has its pre-install tap
     * target. The commit threads the full payload extras ([AppInstallPayload.toExtras]);
     * [AppInstallPayload.fromExtras] re-validates, so a tampered bundle
     * yields null and the post stays action-less rather than pointing
     * nowhere. Legacy result intents (payload extras absent) likewise
     * yield null.
     */
    private fun payloadFrom(intent: Intent): AppInstallPayload? {
        val extras = intent.extras ?: return null
        val map = HashMap<String, String?>(8)
        for (key in extras.keySet()) {
            map[key] = extras.getString(key)
        }
        return AppInstallPayload.fromExtras(map)
    }

    companion object {
        private const val TAG = "AppInstallResult"
    }
}
