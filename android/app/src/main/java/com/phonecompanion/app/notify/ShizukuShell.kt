package com.phonecompanion.app.notify

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.phonecompanion.app.shizuku.ISilentInstallService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Android edge of [ShizukuSilentInstall]: live-binder classification plus
 * the one-shot shell commit.
 *
 * [statusNow]/[shouldOfferRequest] are safe from any thread (every Shizuku
 * static is wrapped — a dead binder reads as UNAVAILABLE, never a throw),
 * but they are binder IPC: call from background or tap paths, and for the
 * pre-install screen re-check on resume (the user may grant in the Shizuku
 * manager app directly). Permission is ONLY ever requested from foreground
 * UI (the pre-install screen) — background receipts never request, they
 * take the dialog path unless already granted.
 *
 * [installOnce] binds the [ShizukuSilentInstallService] UserService, runs
 * one `pm install`, destroys the shell process, and unbinds. Any transport
 * edge (no binder, bind timeout, binder death mid-call) returns
 * [ShizukuSilentInstall.ShellOutcome.BinderDied] so the flow falls back to
 * the dialog path from the cached verified APK — transport trouble NEVER
 * fails the install attempt.
 *
 * Logging discipline: package names and exit codes only.
 */
object ShizukuShell {

    /** Stable tag (class names shift under obfuscation) + service version. */
    private const val SERVICE_TAG = "pc-silent-install"
    private const val SERVICE_VERSION = 1

    /** Binder-connect budget: no grant dialog can appear here, be quick. */
    internal const val BIND_TIMEOUT_MS = 15_000L

    private val probe = object : ShizukuSilentInstall.Probe {
        override fun isSupported(): Boolean = try {
            !Shizuku.isPreV11()
        } catch (_: Exception) {
            false
        }

        override fun isBinderAlive(): Boolean = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

        override fun isPermissionGranted(): Boolean = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        // Shizuku semantics (inverted from AOSP): true means "Deny and
        // don't ask again". Unknown (binder died between checks) fails
        // closed to true so the affordance hides instead of nagging.
        override fun shouldShowRationale(): Boolean = try {
            Shizuku.shouldShowRequestPermissionRationale()
        } catch (_: Exception) {
            true
        }
    }

    /** Current silent-install status against the live binder. */
    fun statusNow(): ShizukuSilentInstall.Status = ShizukuSilentInstall.statusOf(probe)

    /**
     * True when the pre-install UI should show the enable affordance
     * (binder alive, permission missing, still askable). After any denial
     * this is false — ask once, never nag.
     */
    fun shouldOfferRequest(): Boolean = ShizukuSilentInstall.shouldOfferRequest(probe)

    /**
     * Foreground-only permission request (pre-install screen). Never call
     * from background/worker context (background activity-start
     * restrictions). The result arrives via the caller's
     * `OnRequestPermissionResultListener`; a dead binder throws — the
     * caller swallows it and re-renders on resume.
     */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /**
     * One silent commit of the staged APK. Runs on the caller's thread —
     * call from a background coroutine, never the main thread. Always
     * destroys + unbinds before returning (success or not).
     */
    suspend fun installOnce(
        appContext: Context,
        stagedApkAbsolutePath: String,
        bindTimeoutMs: Long = BIND_TIMEOUT_MS,
    ): ShizukuSilentInstall.ShellOutcome = withContext(Dispatchers.IO) {
        val app = appContext.applicationContext
        if (!probe.isBinderAlive()) return@withContext ShizukuSilentInstall.ShellOutcome.BinderDied
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ShizukuSilentInstallService::class.java.name),
        ).daemon(false)
            .processNameSuffix("silent-install")
            .version(SERVICE_VERSION)
            .tag(SERVICE_TAG)
        val connected = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && !connected.isCompleted) connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("shizuku service disconnected"))
                }
            }
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (_: Exception) {
            return@withContext ShizukuSilentInstall.ShellOutcome.BinderDied
        }
        val binder = try {
            withTimeoutOrNull(bindTimeoutMs) { connected.await() }
        } catch (_: Exception) {
            null
        }
        if (binder == null) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            return@withContext ShizukuSilentInstall.ShellOutcome.BinderDied
        }
        val service = ISilentInstallService.Stub.asInterface(binder)
        val raw = try {
            service.installApk(stagedApkAbsolutePath)
        } catch (_: Exception) {
            // Binder died mid-call (or the shell process went away):
            // transport fallback, never a terminal failure.
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            return@withContext ShizukuSilentInstall.ShellOutcome.BinderDied
        }
        // Destroy first (exits the shell process), then unbind: the shell
        // side never lingers past one install.
        runCatching { service.destroy() }
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        ShizukuSilentInstall.parseShellResult(raw)
    }
}
