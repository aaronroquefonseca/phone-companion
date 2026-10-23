package com.phonecompanion.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background poll worker stub.
 * TODO (widgets PR): PeriodicWorkRequest (~hourly, screen-on aware,
 * no exact alarms) calling GET /api/v1/widgets and refreshing Glance.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // TODO (widgets PR): poll + update widgets.
        return Result.success()
    }
}
