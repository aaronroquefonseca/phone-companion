package com.phonecompanion.app.work

import android.content.Context
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background poll worker (widgets PR / P7).
 *
 * Hourly via WorkManager — never exact alarms. Constraints: connected
 * network + battery-not-low. Screen-on awareness: no WorkManager constraint
 * exists for screen state, so doWork() checks PowerManager.isInteractive and
 * returns retry (exponential backoff) while the screen is off instead of
 * polling in the background.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val power = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) {
            // Screen off: back off and let WorkManager retry later.
            return Result.retry()
        }
        return try {
            WidgetPoller.refresh(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_HOURLY = "widget-refresh-hourly"
        private const val MAX_ATTEMPTS = 3

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

        /** Manual refresh entrypoint (WidgetsActivity open + refresh button). */
        fun requestManualRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
