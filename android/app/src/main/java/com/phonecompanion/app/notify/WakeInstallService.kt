package com.phonecompanion.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phonecompanion.app.R
import com.phonecompanion.app.ui.MainActivity
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
 *  2. mirrors the live transfer into its ONE foreground notification
 *     (download percentage, then "Installing…"),
 *  3. stops itself once the in-flight registry empties.
 * Bounded by [MAX_LIFETIME_MS] so a stuck transfer cannot pin the
 * service forever.
 *
 * Notifications: the platform requires a foreground notification, so the
 * install flow does not post a second one while this service is active —
 * [AppInstallNotifications] checks [isActive] and lets the service own
 * the progress/installing state. The terminal post is separate and
 * replaces the service notification after the service stops.
 */
class WakeInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var mirror: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isActive = true
        startInForeground()
        if (work?.isActive != true) {
            work = scope.launch { runOnce() }
        }
        if (mirror?.isActive != true) {
            mirror = scope.launch { mirrorProgress() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        try {
            ensureChannel()
            startForegroundCompat(buildNotification())
        } catch (e: Exception) {
            // FGS not allowed (OEM policy): the caller's inline/worker
            // path still runs; never crash the service start.
            Log.i(TAG, "foreground start refused [${e.javaClass.simpleName}]")
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * One notification, live: shows the current download percentage (or
     * bytes when the length is unknown) and switches to "Installing…"
     * during the commit phase. Re-posting the same id keeps the service
     * in the foreground. Every variant is tappable: the progress registry
     * is deliberately payload-free (push id, package, label, percent — no
     * URLs or hashes), so the tap opens the app rather than the
     * pre-install screen — never a dead tap.
     */
    private fun buildNotification(): Notification {
        val installing = installingLabel
        val entry = AppInstallProgress.entries.value.values.firstOrNull()
        val openApp = openAppIntent()
        return if (installing != null) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Installing ${installing}…")
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .apply { openApp?.let { setContentIntent(it) } }
                .build()
        } else if (entry != null) {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_install_progress_title, entry.label))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .apply { openApp?.let { setContentIntent(it) } }
            val percent = entry.percent
            if (percent != null) {
                builder.setProgress(100, percent, false)
                    .setContentText("${entry.packageName} • $percent%")
            } else {
                builder.setProgress(0, 0, true)
                    .setContentText("${entry.packageName} • downloading")
            }
            builder.build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Checking for installs…")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .apply { openApp?.let { setContentIntent(it) } }
                .build()
        }
    }

    /**
     * Tap target for the foreground notification: opens the app. Built
     * defensively (a dead PendingIntent must never fail the foreground
     * start, which the install flow depends on) and null-safe at every
     * call site.
     */
    private fun openAppIntent(): PendingIntent? = try {
        PendingIntent.getActivity(
            this,
            CompanionMessagingService.actionRequestCode(
                NOTIFICATION_ID,
                AppInstallNotifications.ACTION_OPEN_APP,
            ),
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    } catch (_: Exception) {
        null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background checks",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Brief background checks for pushed app installs."
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private suspend fun mirrorProgress() {
        try {
            AppInstallProgress.entries.collect { entries ->
                try {
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    // A poll that ran before this service started (manual
                    // refresh racing the wake) already posted its own
                    // progress under the per-push id. This service owns the
                    // single visible line now, so drop the duplicate.
                    for (entry in entries.values) {
                        manager.cancel(AppInstallPayload.notifyIdFor(entry.pushId))
                    }
                    manager.notify(NOTIFICATION_ID, buildNotification())
                } catch (_: Exception) {
                    // Mirroring is cosmetic; the transfer is unaffected.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Never fail the service on notification trouble.
        }
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
        isActive = false
        installingLabel = null
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
         * True while the service owns the progress notification. The
         * install flow checks this and skips its own pre-terminal posts so
         * the user never sees a duplicate "Checking" + "Downloading" pair.
         */
        @Volatile
        var isActive: Boolean = false
            private set

        /** Non-null during the commit phase; drives the same notification. */
        @Volatile
        private var installingLabel: String? = null

        /** Switches the shared notification to "Installing…". */
        fun noteInstalling(label: String) {
            installingLabel = label
        }

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
