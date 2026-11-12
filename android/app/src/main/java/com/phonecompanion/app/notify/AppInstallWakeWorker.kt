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
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * FCM-wake worker: the silent app-push wake ([AppInstallCommandPoll.isWake])
 * enqueues this (expedited) instead of rendering anything, and the worker
 * runs the same outbox poll as the scheduled cycle
 * ([AppInstallCommandPoll.pollOnce]) so the auto-download starts instantly
 * instead of waiting for the next poll.
 *
 * NOTE: a FirebaseMessagingService cannot hold itself alive for async
 * work — `goAsync()` exists only on BroadcastReceiver, not on Service —
 * so the wake hands off to WorkManager (which owns the wakelock and the
 * retry/backstop) rather than a detached service coroutine that would die
 * with the process. No screen-on gate here (unlike [RefreshWorker]):
 * a server wake means deliver now, foreground or not.
 *
 * Logging discipline: shape only, never ids/content.
 */
class AppInstallWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Bounded in-worker retries (see pollWithRetries): a transient
        // network blip at wake time must not silently kill instant
        // delivery. pollWithRetries only throws on cancellation
        // (rethrown below); any other failure returns normally after the
        // final attempt — the scheduled poll stays the backstop, never a
        // retry storm off a server outage.
        try {
            pollWithRetries({ AppInstallCommandPoll.pollOnce(applicationContext) })
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
         * Bounded wake-poll attempts: one initial poll plus in-worker
         * retries, then give up and let the scheduled poll be the
         * backstop. Never unbounded, never on cancellation (rethrown).
         */
        internal const val MAX_POLL_ATTEMPTS = 3

        /**
         * In-worker retry delays reuse the enqueue backoff shape
         * (exponential from the same 1-minute base as [wakeRequest]'s
         * `setBackoffCriteria`): a blip costs minutes, not the next
         * hourly poll.
         */
        internal const val RETRY_BACKOFF_BASE = 1L
        internal val RETRY_BACKOFF_UNIT: TimeUnit = TimeUnit.MINUTES

        /** Exponential retry delay after [failures] failed polls (1-based). */
        internal fun retryDelayMs(failures: Int): Long =
            RETRY_BACKOFF_UNIT.toMillis(RETRY_BACKOFF_BASE) * (1L shl (failures - 1))

        /**
         * Runs one wake poll with bounded retries: transport/auth
         * failures (any [Exception]) retry the same poll until
         * [MAX_POLL_ATTEMPTS] total attempts, sleeping the exponential
         * [retryDelayMs] between attempts; then returns normally (the
         * caller still succeeds the work — the scheduled poll is the
         * backstop). Success returns after one attempt with no sleep;
         * [CancellationException] always propagates without retrying.
         *
         * [poll]/[sleeper]/[logInfo] are injectable so unit tests drive
         * the retry loop without a device: production wires the real
         * poll, [delay], and shape-only logging.
         */
        internal suspend fun pollWithRetries(
            poll: suspend () -> Unit,
            sleeper: suspend (Long) -> Unit = { delay(it) },
            logInfo: (String) -> Unit = { Log.i(TAG, it) },
        ) {
            var failures = 0
            while (true) {
                try {
                    poll()
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= MAX_POLL_ATTEMPTS) {
                        logInfo("wake poll aborted error=${e.javaClass.simpleName} attempts=$failures")
                        return
                    }
                    logInfo("wake poll retry failures=$failures error=${e.javaClass.simpleName}")
                    sleeper(retryDelayMs(failures))
                }
            }
        }

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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_BASE, RETRY_BACKOFF_UNIT)
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
