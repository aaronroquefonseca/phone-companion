package com.phonecompanion.app.work

import android.content.Context
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phonecompanion.app.data.loadOrFallback
import java.util.concurrent.TimeUnit

/**
 * Background poll worker (widgets PR / P7).
 *
 * Hourly via WorkManager — never exact alarms. Constraints: connected
 * network + battery-not-low. Screen-on awareness: no WorkManager constraint
 * exists for screen state, so doWork() checks PowerManager.isInteractive and
 * returns retry (exponential backoff) while the screen is off instead of
 * polling in the background. Both the screen-off path and failures give up
 * after [MAX_ATTEMPTS] attempts.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val power = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) {
            // Screen off: back off and let WorkManager retry later, but honor
            // the attempt cap like any other failure.
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        // Cancellation is rethrown by loadOrFallback (never converted to
        // retry/failure); other failures retry until the attempt cap.
        return loadOrFallback(if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()) {
            WidgetPoller.refresh(applicationContext)
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_HOURLY = "widget-refresh-hourly"
        private const val UNIQUE_MANUAL = "widget-refresh-manual"
        private const val MAX_ATTEMPTS = 3

        /**
         * Unique-work name for the manual refresh, exposed for the
         * [com.phonecompanion.app.ui.WidgetsRefreshViewModel] observer.
         * (Wiring it into WidgetsActivity is a follow-up: that file is
         * owned by the parallel poll-wipe agent.)
         */
        fun manualWorkName(): String = UNIQUE_MANUAL

        private fun hourlyConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Idempotent hourly schedule; call from Application/Activity startup. */
        fun scheduleHourly(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(hourlyConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_HOURLY,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Manual refresh entrypoint (WidgetsActivity open + refresh button).
         * Unique REPLACE so rapid calls (open + button) collapse to a single
         * refresh instead of stacking duplicate work. Expedited (falling
         * back to a normal request past quota) so a user tap runs NOW
         * instead of sitting in OEM deferral queues — the old deferrable
         * job could stall for minutes on Samsung-class devices while the
         * UI claimed "Refresh requested". Constraints + backoff unchanged.
         */
        fun requestManualRefresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_MANUAL,
                ExistingWorkPolicy.REPLACE,
                manualRefreshRequest(),
            )
        }

        /**
         * The manual-refresh request, split out for unit tests: expedited
         * must survive refactors (a quiet drop back to deferrable re-hides
         * the "Refresh now lies" bug this flag fixed).
         */
        internal fun manualRefreshRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
    }
}
