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
import com.phonecompanion.app.data.ApiClient
import com.phonecompanion.app.data.SecureStorage
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
 * Instant mode: a persistent foreground service that holds the backend's
 * long-poll (`GET /api/v1/events/wait`, 300 s hold) so a queued command
 * reaches the phone in about a second regardless of screen state, Doze or
 * FCM batching.
 *
 * This exists because the logcat evidence showed the app-side wake path
 * healthy (whitelisted, FGS start allowed) while FCM delivery itself was
 * batched until the screen came on. A held connection removes that
 * dependency entirely: the server answers the held request the moment
 * something is queued.
 *
 * Cost, stated honestly: one persistent low-importance notification and a
 * mostly-idle socket. It is opt-in (home-screen toggle) and stops itself
 * on unpair/revocation.
 */
class InstantModeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            InstantMode.disable(this)
            stopSelf()
            return START_NOT_STICKY
        }
        isActive = true
        startInForeground()
        if (work?.isActive != true) {
            work = scope.launch { loop() }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        try {
            ensureChannel()
            startForegroundCompat(buildNotification())
        } catch (e: Exception) {
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

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, InstantModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Instant delivery on")
            .setContentText("Connected to your server — commands arrive immediately.")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Instant delivery",
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.description = "Holds a connection so pushed commands arrive immediately."
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private suspend fun loop() {
        val app = applicationContext
        val storage = SecureStorage.getInstance(app)
        while (isActive) {
            val baseUrl = storage.baseUrl
            val token = storage.deviceToken
            if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
                InstantMode.disable(app)
                break
            }
            try {
                val api = ApiClient.createLongPoll(baseUrl) { storage.deviceToken }
                val response = api.waitForEventsRaw()
                when {
                    response.code() == 401 || response.code() == 403 -> {
                        // Revoked or unpaired: stop silently; the widget
                        // poll owns the revocation UX.
                        Log.i(TAG, "instant mode stopped [auth=${response.code()}]")
                        InstantMode.disable(app)
                        break
                    }
                    response.isSuccessful -> {
                        // Wake (or 300 s idle timeout): a poll is cheap and
                        // also drains any queued install/shell command.
                        AppInstallCommandPoll.pollOnce(app)
                    }
                    else -> delay(RETRY_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(RETRY_MS)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        isActive = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "InstantMode"
        private const val CHANNEL_ID = "pc_instant"
        private const val NOTIFICATION_ID = 0x5043_0002
        private const val RETRY_MS = 5_000L
        const val ACTION_STOP = "pc_instant_stop"

        @Volatile
        var isActive: Boolean = false
            private set

        fun start(context: Context): Boolean = try {
            InstantMode.setEnabled(context, true)
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, InstantModeService::class.java),
            )
            true
        } catch (e: Exception) {
            Log.i(TAG, "start refused [${e.javaClass.simpleName}]")
            false
        }

        fun stop(context: Context) {
            InstantMode.setEnabled(context, false)
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, InstantModeService::class.java),
                )
            } catch (_: Exception) {
                // Already stopped.
            }
        }
    }
}

/** Persisted on/off state for instant mode (survives process death). */
object InstantMode {

    private const val PREFS = "pc_ui"
    private const val KEY = "instant_mode"

    fun isEnabled(context: Context): Boolean = try {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    } catch (_: Exception) {
        false
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, enabled).apply()
        } catch (_: Exception) {
            // Persisting the preference is best-effort.
        }
    }

    fun disable(context: Context) = setEnabled(context, false)

    /**
     * Restarts the service when the user left it enabled but the process
     * (and therefore the service) died — typically after an app update,
     * where the system refuses a foreground-service start from the update
     * broadcast. Called from any FCM message, which is an allowed
     * background-FGS exemption and happens at least once per push.
     */
    fun revive(context: Context) {
        if (!isEnabled(context)) return
        if (InstantModeService.isActive) return
        runCatching { InstantModeService.start(context) }
    }
}
