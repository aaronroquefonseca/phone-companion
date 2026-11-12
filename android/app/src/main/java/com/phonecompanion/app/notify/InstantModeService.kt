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
import com.phonecompanion.app.data.ApiService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
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
        if (intent?.action == ACTION_PROJECTION) {
            // Order matters: the service must already be foreground with
            // the mediaProjection type when getMediaProjection runs, or
            // the call throws SecurityException.
            isActive = true
            startInForeground()
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (data != null) ScreenProjection.start(this, code, data)
            return START_STICKY
        }
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
        if (!InstantMode.isEnabled(this)) {
            // Session lease: stop by itself when the agent's window ends.
            scope.launch { watchLease() }
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
            var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ScreenProjection.isReady || ScreenProjection.pending) {
                types = types or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, types)
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
                val api = ApiClient.createStreaming(baseUrl) { storage.deviceToken }
                val response = api.deviceLink()
                when {
                    response.code() == 404 -> {
                        // Backend predates the fast link: keep the old
                        // long-poll behaviour so instant mode still works
                        // during a rolling upgrade.
                        legacyPoll(app, baseUrl)
                        delay(RETRY_MS)
                    }
                    response.code() == 401 || response.code() == 403 -> {
                        // Revoked or unpaired: stop silently; the widget
                        // poll owns the revocation UX.
                        Log.i(TAG, "instant mode stopped [auth=${response.code()}]")
                        InstantMode.disable(app)
                        break
                    }
                    response.isSuccessful -> {
                        val body = response.body()
                        if (body == null) {
                            delay(RETRY_MS)
                        } else {
                            body.use { readLink(app, api, it) }
                            delay(RETRY_MS)
                        }
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


    private suspend fun watchLease() {
        while (isActive) {
            delay(20_000)
            if (InstantMode.isEnabled(this)) return
            if (!InstantMode.hasLease(this)) {
                Log.i(TAG, "session lease ended; stopping")
                stopSelf()
                return
            }
        }
    }

    /** One command off the fast link: run it, ship the bytes back. */
    private suspend fun readLink(app: Context, api: ApiService, body: ResponseBody) {
        // One binder for the whole link lifetime: per-command binds cost
        // more than the command itself.
        val shell = ShizukuRemoteShell.Session(app)
        LinkOps.session = shell
        try {
            readLinkLoop(app, api, body)
        } finally {
            LinkOps.session = null
            shell.close()
        }
    }

    private suspend fun readLinkLoop(app: Context, api: ApiService, body: ResponseBody) {
        val source = body.source()
        // The link replaces the outbox long-poll, so it also has to drain
        // the outbox (installs, legacy shell). Every ping is a cheap
        // reminder; 30 s is enough for installs and never competes with
        // link commands.
        var lastOutboxPoll = 0L
        while (isActive) {
            val line = source.readUtf8Line() ?: return
            if (line.isBlank()) continue
            val obj = try {
                JSONObject(line)
            } catch (_: Exception) {
                continue
            }
            if (obj.optString("t") == "ping") {
                if (System.currentTimeMillis() - lastOutboxPoll > 30_000L) {
                    lastOutboxPoll = System.currentTimeMillis()
                    runCatching { AppInstallCommandPoll.pollOnce(app) }
                }
                continue
            }
            val opId = obj.optString("op_id")
            if (opId.isEmpty()) continue
            val kind = obj.optString("kind")
            val args = obj.optJSONObject("args") ?: JSONObject()
            val t0 = System.currentTimeMillis()
            val result = try {
                LinkOps.execute(app, kind, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "link op '$kind' failed [${e.javaClass.simpleName}]")
                LinkOps.Result(
                    "application/json",
                    JSONObject().put("error", e.message ?: "failed").toString().toByteArray(),
                    502,
                )
            }
            val t1 = System.currentTimeMillis()
            try {
                val media = result.contentType.toMediaTypeOrNull()
                    ?: "application/octet-stream".toMediaType()
                api.postLinkResult(
                    opId,
                    result.contentType,
                    result.status,
                    result.bytes.toRequestBody(media),
                ).body()?.close()
                Log.i(TAG, "link op $kind exec=${t1 - t0}ms upload=${System.currentTimeMillis() - t1}ms bytes=${result.bytes.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The server drops unknown/expired ops, so a failed upload
                // only matters for the next command, not this one.
            }
        }
    }

    /** Pre-link fallback: the old 300 s outbox long poll. */
    private suspend fun legacyPoll(app: Context, baseUrl: String) {
        val api = ApiClient.createLongPoll(baseUrl) {
            SecureStorage.getInstance(app).deviceToken
        }
        val response = api.waitForEventsRaw()
        if (response.isSuccessful) AppInstallCommandPoll.pollOnce(app)
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
        const val ACTION_PROJECTION = "pc_projection"
        const val EXTRA_RESULT_CODE = "pc_projection_code"
        const val EXTRA_RESULT_DATA = "pc_projection_data"

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

    private const val KEY_LEASE = "instant_lease_until"

    /**
     * Agent-driven session: hold the link for [ttlSeconds] even when the
     * user's persistent toggle is off, then let it lapse on its own. This
     * is the battery-friendly mode — no always-on service, but a warm
     * connection for as long as an agent is actually working.
     */
    fun startWithLease(context: Context, ttlSeconds: Long) {
        val until = System.currentTimeMillis() + ttlSeconds.coerceIn(30, 3600) * 1000
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LEASE, until).apply()
        } catch (_: Exception) {
            return
        }
        runCatching { InstantModeService.start(context) }
    }

    fun leaseUntil(context: Context): Long = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LEASE, 0L)
    } catch (_: Exception) {
        0L
    }

    fun clearLease(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LEASE).apply()
        } catch (_: Exception) {
        }
    }

    /** True while a session lease is still running. */
    fun hasLease(context: Context): Boolean = leaseUntil(context) > System.currentTimeMillis()
}
