package com.phonecompanion.app.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Android edge of [ShizukuSilentInstall]: live-binder classification plus
 * the privileged-session commit.
 *
 * [statusNow]/[shouldOfferRequest] are safe from any thread (every Shizuku
 * static is wrapped — a dead binder reads as UNAVAILABLE, never a throw),
 * but they are binder IPC: call from background or tap paths, and for the
 * pre-install screen re-check on resume (the user may grant in the Shizuku
 * manager app directly). Permission is ONLY ever requested from foreground
 * UI (the pre-install screen) — background receipts never request, they
 * take the dialog path unless already granted.
 *
 * [commitPrivileged] builds a `PackageInstaller` on the Shizuku-held
 * `package` service binder (shell privilege): a MODE_FULL_INSTALL session
 * (replace-existing semantics) for the verified staged APK, bytes written
 * from our own storage, committed with a status receiver that is only
 * ever observed — a user-confirmation intent is never launched here,
 * ever. Any transport edge (no binder, grant lost, commit threw, session
 * unwritable) returns
 * [ShizukuSilentInstall.PrivilegedOutcome.TransportDied] so the flow
 * falls back to the dialog path from the cached verified APK — transport
 * trouble NEVER fails the install attempt.
 *
 * The commit outcome is never trusted on its own: the flow reads back
 * [awaitInstalledVersion] and requires the installed versionCode to equal
 * the staged one ([ShizukuSilentInstall.isVerifiedInstall]) before
 * reporting success.
 *
 * Multi-user: the privileged installer targets the current user only
 * (resolved reflectively — `Context.getUserId` is hidden platform API);
 * work-profile targeting is future work, not implemented here.
 *
 * Logging discipline: package names and status ints only — never APK
 * paths, URLs, hashes, tokens, or installer output.
 */
object ShizukuPrivilegedInstall {

    private const val TAG = "ShizukuPrivileged"

    /** Status-receiver broadcast base; the install attempt nonce suffixes it. */
    internal const val ACTION_SILENT_RESULT = "pc_shizuku_silent_result"

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
     * Copies the verified APK to the staged location (same dir as before;
     * shell readability is no longer required — the session writer is our
     * own process). Called only AFTER a passed SHA-256 verify, and only
     * on the silent path. Returns the staged file, or null when staging
     * is impossible — the caller falls back to the dialog flow.
     * Call from a background thread (blocking copy).
     */
    fun stagePrivileged(appContext: Context, pushId: String, verifiedApk: File): File? {
        val app = appContext.applicationContext
        return try {
            val staged = AppInstallFiles.stagedApkFile(app, pushId) ?: return null
            staged.parentFile?.mkdirs()
            verifiedApk.copyTo(staged, overwrite = true)
            staged.takeIf { it.isFile }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses the staged APK's versionCode once (before commit) via
     * `PackageManager.getPackageArchiveInfo`. Null when the APK cannot be
     * parsed — the caller fails closed to the dialog path (unknown
     * state, never a silent success). Call from a background thread.
     */
    fun stagedVersionCode(appContext: Context, stagedApk: File): Long? {
        return try {
            val pm = appContext.applicationContext.packageManager
            val info = pm.getPackageArchiveInfo(stagedApk.absolutePath, 0) ?: return null
            versionCodeOf(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One privileged silent commit of the staged APK. Runs on the caller's
     * thread — call from a background coroutine, never the main thread.
     * Never throws for install failures (they are outcomes); any
     * transport edge surfaces as
     * [ShizukuSilentInstall.PrivilegedOutcome.TransportDied].
     */
    suspend fun commitPrivileged(
        appContext: Context,
        stagedApk: File,
        packageName: String,
        attempt: String,
        statusTimeoutMs: Long = ShizukuSilentInstall.COMMIT_STATUS_TIMEOUT_MS,
    ): ShizukuSilentInstall.PrivilegedOutcome = withContext(Dispatchers.IO) {
        val app = appContext.applicationContext
        if (!probe.isBinderAlive()) {
            return@withContext ShizukuSilentInstall.PrivilegedOutcome.TransportDied
        }
        if (!ShizukuSilentInstall.isStagedApkPath(stagedApk.absolutePath)) {
            Log.w(TAG, "commitPrivileged refused path shape for $packageName")
            return@withContext ShizukuSilentInstall.PrivilegedOutcome.TransportDied
        }
        var sessionId = -1
        var installer: PrivilegedInstaller? = null
        try {
            installer = privilegedInstaller(app)
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            params.setAppPackageName(packageName)
            // INSTALL_REPLACE_EXISTING is REQUIRED: the session is created
            // by the shell peer, and PMS only auto-adds replace-existing
            // for app callers — without it every update fails with
            // INSTALL_FAILED_ALREADY_EXISTS.
            val flags = ShizukuSilentInstall.applyInstallFlags(params)
            if (!flags.applied) {
                Log.w(TAG, "install flags not applied for $packageName [field-absent]")
            }
            // Session trust attributes (originating UID, user-initiated
            // reason, no-user-action). With the wrapped binders below the
            // session already runs as shell, so these are defense in depth;
            // each fails open individually.
            val trust = ShizukuSilentInstall.applySessionTrust(
                params,
                peerUid(),
                Build.VERSION.SDK_INT,
                ShizukuSilentInstall.resolveInstallReasonUser(PackageManager::class.java),
                ShizukuSilentInstall.resolveUserActionNotRequired(params.javaClass),
            )
            Log.i(
                TAG,
                "commitPrivileged trust ou=${trust.originatingUid} " +
                    "reason=${trust.installReason} action=${trust.userAction} for $packageName",
            )
            val userId = Context::class.java.getMethod("getUserId").invoke(app) as Int
            sessionId = installer.createSession(params, userId)
            Log.i(TAG, "commitPrivileged session=$sessionId created for $packageName")
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, -1).use { out ->
                    stagedApk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val status = awaitCommitStatus(app, session, packageName, attempt, statusTimeoutMs)
                Log.i(TAG, "commitPrivileged status=$status for $packageName")
                ShizukuSilentInstall.PrivilegedOutcome.Committed(status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log the class (never paths/hashes/URLs) so a failed silent
            // attempt is diagnosable instead of silently degrading.
            Log.w(TAG, "commitPrivileged failed for $packageName [${e.javaClass.simpleName}]")
            if (sessionId >= 0) runCatching { installer?.abandonSession(sessionId) }
            ShizukuSilentInstall.PrivilegedOutcome.TransportDied
        }
    }

    /**
     * Polls the installed versionCode until it equals [expectedVersionCode]
     * or [graceMs] elapses. Returns the last observed versionCode (null
     * when the package never appeared). The caller decides via
     * [ShizukuSilentInstall.isVerifiedInstall] — this function only
     * waits, capped. Reads OUR OWN PackageManager: no Shizuku binder is
     * needed here, so a binder dying mid-install does not block the
     * read-back. Call from a background coroutine.
     */
    suspend fun awaitInstalledVersion(
        appContext: Context,
        packageName: String,
        expectedVersionCode: Long,
        graceMs: Long = ShizukuSilentInstall.VERIFY_GRACE_MS,
        pollMs: Long = ShizukuSilentInstall.VERIFY_POLL_MS,
    ): Long? = withContext(Dispatchers.IO) {
        val pm = appContext.applicationContext.packageManager
        val deadline = SystemClock.elapsedRealtime() + graceMs
        var last: Long?
        do {
            last = installedVersionCode(pm, packageName)
            if (last != null && last == expectedVersionCode) break
            try {
                delay(pollMs)
            } catch (e: CancellationException) {
                throw e
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return@withContext last
    }

    /** Single installed-versionCode read; null when absent/unreadable. */
    internal fun installedVersionCode(pm: PackageManager, packageName: String): Long? {
        return try {
            val info = pm.getPackageInfo(packageName, 0)
            versionCodeOf(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** API-compat versionCode (`getLongVersionCode` is API 28+). */
    internal fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /**
     * Shell package name used as installer identity on non-root Shizuku.
     * Retained for reference/logging only: with the wrapped binder, the
     * caller UID IS shell, and PackageManagerService nulls the installer
     * name for shell callers itself.
     */
    const val SHELL_INSTALLER_PACKAGE = "com.android.shell"

    /** Shizuku peer UID, or null when the binder is dead. Root is 0. */
    fun peerUid(): Int? = try {
        Shizuku.getUid()
    } catch (_: Exception) {
        null
    }

    /**
     * Builds the privileged installer. THE LOAD-BEARING PART: every
     * binder in the chain must be wrapped in [ShizukuBinderWrapper] so
     * the transacting process is the Shizuku peer (shell/root) — not our
     * app. Wrapping only the one-time `IPCackageManager` lookup (the
     * previous implementation) left `createSession`/`openSession`/
     * `commit` running as our own UID; PackageManagerService then strips
     * INSTALL_FROM_ADB, rejects the `com.android.shell` installer name
     * (SecurityException) and requires the confirmation dialog.
     * Mirrors ackpine's ShizukuPackageInstaller: wrap the
     * `IPackageInstaller` binder, and wrap each `IPackageInstallerSession`
     * binder returned by `openSession`. Hidden-API access via
     * HiddenApiBypass (Apache-2.0, version-pinned) — reflection only.
     * Throws on any edge (the caller maps it to transport fallback).
     */
    private fun privilegedInstaller(app: Context): PrivilegedInstaller {
        // Prefixes without a trailing ';' so nested Stub/Proxy classes
        // (e.g. IPackageManager$Stub$Proxy, which declares the invoked
        // methods) still match the exemption.
        HiddenApiBypass.addHiddenApiExemptions(
            "Landroid/content/pm/IPackageManager",
            "Landroid/content/pm/IPackageInstaller",
            "Landroid/content/pm/IPackageInstallerSession",
            "Landroid/content/pm/PackageInstaller",
            "Landroid/content/pm/PackageManager",
            "Landroid/content/pm/ParceledListSlice",
            "Landroid/content/Context",
        )
        val pmBinder: IBinder = SystemServiceHelper.getSystemService("package")
            ?: throw IllegalStateException("no package service binder")
        val ipmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val ipm = ipmStub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(pmBinder))
            ?: throw IllegalStateException("no IPackageManager")
        val rawIpi = ipm.javaClass.getMethod("getPackageInstaller").invoke(ipm)
            ?: throw IllegalStateException("no IPackageInstaller")
        val ipiBinder = rawIpi.javaClass.getMethod("asBinder").invoke(rawIpi) as IBinder
        val ipiStub = Class.forName("android.content.pm.IPackageInstaller\$Stub")
        val ipi = ipiStub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(ipiBinder))
            ?: throw IllegalStateException("no wrapped IPackageInstaller")
        return PrivilegedInstaller(ipi)
    }

    /**
     * `IPackageInstaller` proxy whose binder is Shizuku-wrapped, plus
     * wrapped sessions. Reflective calls only (hidden AIDL surfaces):
     * `createSession(params, installerName, [attributionTag,] userId)`,
     * `openSession(id)`, `abandonSession(id)`. `openSession` additionally
     * wraps the returned `IPackageInstallerSession` binder and rebuilds a
     * `PackageInstaller.Session` over it through the hidden
     * `Session(IPackageInstallerSession)` constructor — the only way to
     * make `openWrite`/`fsync`/`commit` transact as the Shizuku peer.
     */
    private class PrivilegedInstaller(private val ipi: Any) {

        fun createSession(params: PackageInstaller.SessionParams, userId: Int): Int {
            // Installer name null: PMS derives it from the (shell) caller.
            val args: Array<Any?> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                arrayOf(params, null, null, userId)
            } else {
                arrayOf(params, null, userId)
            }
            val types: Array<Class<*>> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                arrayOf(
                    PackageInstaller.SessionParams::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                )
            } else {
                arrayOf(
                    PackageInstaller.SessionParams::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                )
            }
            val method = ipi.javaClass.getMethod("createSession", *types)
            return method.invoke(ipi, *args) as Int
        }

        fun openSession(sessionId: Int): PackageInstaller.Session {
            val open = ipi.javaClass.getMethod("openSession", Int::class.javaPrimitiveType)
            val proxy = open.invoke(ipi, sessionId)
                ?: throw IllegalStateException("null session proxy")
            val sessionBinder = proxy.javaClass.getMethod("asBinder").invoke(proxy) as IBinder
            val sessionStub = Class.forName("android.content.pm.IPackageInstallerSession\$Stub")
            val wrapped = sessionStub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(sessionBinder))
                ?: throw IllegalStateException("null wrapped session")
            val ctor = PackageInstaller.Session::class.java.getDeclaredConstructor(
                Class.forName("android.content.pm.IPackageInstallerSession"),
            )
            ctor.isAccessible = true
            return ctor.newInstance(wrapped) as PackageInstaller.Session
        }

        fun abandonSession(sessionId: Int) {
            ipi.javaClass.getMethod("abandonSession", Int::class.javaPrimitiveType)
                .invoke(ipi, sessionId)
        }
    }

    /**
     * Commits the open session and awaits the installer status broadcast
     * (up to [statusTimeoutMs]). The receiver only OBSERVES: a
     * PENDING_USER_ACTION status is returned as-is and never launched —
     * privileged sessions must not need UI, and the post-commit read-back
     * fails such states closed to the dialog path anyway. Returns the
     * status int, or null when none arrived in time (unknown — the
     * read-back still decides).
     */
    private suspend fun awaitCommitStatus(
        app: Context,
        session: PackageInstaller.Session,
        packageName: String,
        attempt: String,
        statusTimeoutMs: Long,
    ): Int? {
        val action = "$ACTION_SILENT_RESULT.$attempt"
        val received = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
                if (!received.isCompleted) received.complete(status)
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val pending = PendingIntent.getBroadcast(
                app,
                attempt.hashCode(),
                Intent(action).setPackage(app.packageName),
                AppInstallInstaller.resultIntentFlags(Build.VERSION.SDK_INT),
            )
            session.commit(pending.intentSender)
            return withTimeoutOrNull(statusTimeoutMs) { received.await() }
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}
