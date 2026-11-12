package com.phonecompanion.app.notify

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * FCM-wake worker (backstop): the silent app-push wake
 * ([AppInstallCommandPoll.isWake]) first polls the outbox INLINE inside
 * `onMessageReceived` (see `CompanionMessagingService`), and only enqueues
 * this (expedited) when the inline fetch fails or times out — plus the
 * usual next-scheduled-poll safety net behind that.
 *
 * Once enqueued the worker runs the same outbox poll as the scheduled
 * cycle ([AppInstallCommandPoll.pollOnce]) so the auto-download still
 * starts without waiting for the next poll.
 *
 * NOTE: only the SYNCHRONOUS inline fetch runs in the service (FCM
 * holds the service alive for the duration of `onMessageReceived`; a
 * Service has no `goAsync()`, so a detached service coroutine would die
 * with the process). The worker handoff owns the wakelock and the
 * retry/backstop for everything past the inline timeout. No screen-on
 * gate here (unlike [RefreshWorker]): a server wake means deliver now,
 * foreground or not.
 *
 * Logging discipline: shape only, never ids/content.
 */
class AppInstallWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Best-effort: pollOnce never throws except on cancellation
        // (which propagates); a failed poll still succeeds the work —
        // the next scheduled poll is the backstop, never a retry storm
        // off a server outage.
        try {
            AppInstallCommandPoll.pollOnce(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "wake poll aborted error=${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        internal const val TAG = "AppInstallWake"
        private const val UNIQUE_WAKE = "app-install-wake"

        /**
         * The wake request, split out for unit tests: expedited must
         * survive refactors (a quiet drop back to deferrable re-hides the
         * instant-delivery regression this worker exists for).
         */
        internal fun wakeRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AppInstallWakeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

        /**
         * Enqueues the wake poll (unique REPLACE: rapid re-wakes collapse
         * to one poll). Never throws — enqueue failure just leaves the
         * next scheduled poll as the backstop.
         */
        fun enqueue(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WAKE,
                    ExistingWorkPolicy.REPLACE,
                    wakeRequest(),
                )
            }
        }
    }
}
