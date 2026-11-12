package com.phonecompanion.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phonecompanion.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground host for wake-triggered install work.
 *
 * The FCM wake handler starts this service (high-priority FCM grants the
 * background FGS start). Without it the poll/download/commit ran in a
 * detached coroutine owned by the app process: once
 * `onMessageReceived` returned, the process could be frozen by Doze or
 * killed by Samsung mid-install, leaving the "Installing…" notification
 * forever with no terminal post — exactly the field report.
 *
 * A foreground service keeps the process in the foreground state (no
 * Doze freeze, no background network restriction) for the duration of
 * the transfer. The service:
 *  1. runs one outbox poll,
 *  2. waits until every install download it started has reached its
 *     terminal (the [AppInstallProgress] registry is empty), then
 *  3. stops itself.
 * Bounded by [MAX_LIFETIME_MS] so a stuck transfer cannot pin the
 * service forever; the progress notification is already the user-visible
 * surface.
 *
 * Its own notification is a silent, low-importance "checking" line that
 * lives only while the service runs; the real progress/terminal posts
 * come from [AppInstallNotifications] on the install channel.
 */
class WakeInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (work?.isActive != true) {
            work = scope.launch { runOnce() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        try {
            ensureChannel()
            val notification: Notification =
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Checking for installs…")
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .setSilent(true)
                    .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // FGS not allowed (OEM policy): the caller's inline/worker
            // path still runs; never crash the service start.
            Log.i(TAG, "foreground start refused [${e.javaClass.simpleName}]")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background checks",
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.description = "Brief background checks for pushed app installs."
        manager.createNotificationChannel(channel)
    }

    private suspend fun runOnce() {
        try {
            AppInstallCommandPoll.pollOnce(applicationContext)
            // The poll starts downloads synchronously (progress registry is
            // populated before begin() returns). Keep the process alive
            // until every in-flight transfer has reached a terminal.
            val deadline = System.currentTimeMillis() + MAX_LIFETIME_MS
            while (System.currentTimeMillis() < deadline) {
                if (AppInstallProgress.entries.value.isEmpty()) break
                delay(POLL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "wake install work aborted [${e.javaClass.simpleName}]")
        } finally {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeInstallService"
        private const val CHANNEL_ID = "pc_background_checks"
        private const val NOTIFICATION_ID = 0x5043_0001
        private const val POLL_MS = 1_000L
        private const val MAX_LIFETIME_MS = 15L * 60L * 1000L

        /**
         * Starts the service. Returns true when the platform accepted the
         * foreground start; false means the caller must fall back to the
         * inline/worker path.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, WakeInstallService::class.java),
            )
            true
        } catch (e: Exception) {
            Log.i(TAG, "start refused [${e.javaClass.simpleName}]")
            false
        }
    }
}
