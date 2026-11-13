package com.phonecompanion.app.notify

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.phonecompanion.app.R
import java.util.concurrent.TimeUnit

/**
 * Backstop for a swallowed installer result.
 *
 * After the receiver launches the system confirmation
 * ([AppInstallResultReceiver.launchConfirmation]) the follow-up result
 * broadcast normally owns the terminal post. Some devices (observed:
 * Samsung + a signing-key mismatch) refuse the install without ever
 * reporting a status back — no broadcast, no notification, dead silence.
 * This worker is enqueued at confirmation-launch time and, if no
 * terminal has been claimed for the attempt by then, posts the FAILED
 * terminal itself (transient copy + Retry, which resumes from the kept
 * cached APK).
 *
 * It posts WITHOUT claiming ([AppInstallNotifications.notifyTerminal]
 * with `claim = false`, guarded by a read-only
 * [AppInstallSessions.isTerminalClaimed] check): if the real result
 * lands late, its claim succeeds and the same notifyId replaces this
 * backstop note — the truth always wins, the watchdog never steals the
 * exactly-once claim. Unique work is keyed per push id with REPLACE, so
 * a re-push or retry collapses to one pending watchdog.
 *
 * Delay is inexact WorkManager scheduling (never an exact alarm — repo
 * battery rule); firing late is harmless, firing early is bounded by the
 * claim check.
 *
 * Logging discipline: package names and push ids only.
 */
class AppInstallResultWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val extras = HashMap<String, String?>(INPUT_KEYS.size)
        for (key in INPUT_KEYS) inputData.getString(key)?.let { extras[key] = it }
        val pushId = extras[AppInstallPayload.EXTRA_PUSH_ID].orEmpty()
        val packageName = extras[AppInstallPayload.EXTRA_PACKAGE].orEmpty()
        if (pushId.isEmpty() || packageName.isEmpty()) return Result.success()
        val attempt = AppInstallPayload.attemptFromExtras(extras)
        // The installer result arrived in time: its terminal owns the
        // post, the watchdog stays silent.
        if (AppInstallSessions.isTerminalClaimed(pushId, attempt)) return Result.success()
        AppInstallNotifications.notifyTerminal(
            applicationContext,
            AppInstallFlow.TerminalKind.FAILED,
            AppInstallNotifications.TerminalTarget(
                pushId,
                packageName,
                extras[AppInstallPayload.EXTRA_LABEL],
            ),
            attempt,
            detail = applicationContext.getString(R.string.app_install_no_result_detail),
            payload = AppInstallPayload.fromExtras(extras),
            claim = false,
        )
        return Result.success()
    }

    companion object {
        /** Unique-work name is per push id: one pending watchdog per push. */
        internal const val UNIQUE_PREFIX = "app-install-result-watchdog-"

        /**
         * Confirmation dialogs usually resolve in seconds; two minutes
         * of slack keeps a slow-but-alive dialog from being declared dead
         * while still catching a swallowed result promptly.
         */
        internal const val DELAY_MINUTES = 2L

        /**
         * Extras the watchdog carries: enough to rebuild the validated
         * payload (Retry tap target + detail intent) plus the attempt
         * nonce for the claim check. All string-typed, well under
         * WorkManager's 10 KB Data cap (URL alone is bounded at 2 KB).
         */
        private val INPUT_KEYS = listOf(
            AppInstallPayload.EXTRA_COMMAND_ID,
            AppInstallPayload.EXTRA_PUSH_ID,
            AppInstallPayload.EXTRA_APK_URL,
            AppInstallPayload.EXTRA_SHA256,
            AppInstallPayload.EXTRA_PACKAGE,
            AppInstallPayload.EXTRA_VERSION,
            AppInstallPayload.EXTRA_LABEL,
            AppInstallPayload.EXTRA_ATTEMPT,
        )

        /**
         * Schedules the backstop for this confirmation round. Never
         * throws — enqueue failure just leaves the swallowed-result case
         * as it was (silent), never breaks the install flow.
         */
        fun enqueue(context: Context, extras: Map<String, String?>) {
            val pushId = extras[AppInstallPayload.EXTRA_PUSH_ID].orEmpty()
            if (pushId.isEmpty()) return
            val builder = Data.Builder()
            for (key in INPUT_KEYS) extras[key]?.let { builder.putString(key, it) }
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_PREFIX + pushId,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AppInstallResultWatchdogWorker>()
                        .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
                        .setInputData(builder.build())
                        .build(),
                )
            }
        }
    }
}
