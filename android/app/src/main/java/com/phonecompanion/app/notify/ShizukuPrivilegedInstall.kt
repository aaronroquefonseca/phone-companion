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
        try {
            val installer = privilegedInstaller(app)
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            params.setAppPackageName(packageName)
            // Trust the session like an `adb install`: without
            // INSTALL_FROM_ADB, PackageManagerService treats this session
            // as an ordinary app-initiated install and posts the system
            // confirmation dialog — exactly the production symptom this
            // fixes. Fail-open (see markSessionInstallFromAdb): when the
            // hidden field/setter is absent the session proceeds unflagged
            // exactly as before, and the dialog fallback + read-back
            // verification decide the outcome.
            markSessionInstallFromAdb(params, packageName)
            // ackpine-proven silent shape (originating UID = Shizuku peer,
            // user-initiated reason, no user action required): without these
            // the system treats the privileged session as app-initiated and
            // posts the confirmation dialog. Each attribute fails open
            // (absent APIs leave the session as before); the dialog fallback
            // + read-back verification decide the outcome.
            val trust = ShizukuSilentInstall.applySessionTrust(
                params,
                peerUid(),
                Build.VERSION.SDK_INT,
                ShizukuSilentInstall.resolveInstallReasonUser(PackageManager::class.java),
                ShizukuSilentInstall.resolveUserActionNotRequired(params.javaClass),
            )
            Log.i(TAG, "commitPrivileged trust ou=${trust.originatingUid} reason=${trust.installReason} action=${trust.userAction} for $packageName")
            if (!ShizukuSilentInstall.isStagedApkPath(stagedApk.absolutePath)) {
                Log.w(TAG, "commitPrivileged refused path shape for $packageName")
                return@withContext ShizukuSilentInstall.PrivilegedOutcome.TransportDied
            }
            var sessionId = -1
            try {
                sessionId = installer.createSession(params)
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
            } catch (_: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                ShizukuSilentInstall.PrivilegedOutcome.TransportDied
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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
     * Best-effort adb-trust flag for one privileged session (see the
     * call site in [commitPrivileged] for WHY). Fail-open: a missing
     * hidden field resolves to the fallback constant, a missing setter
     * no-ops — either way the session proceeds unflagged exactly as
     * before (the system may prompt; the dialog fallback + read-back
     * verification decide the outcome). Never throws, never silently
     * claims success. Logging discipline: package names and exception
     * class shapes only.
     */
    private fun markSessionInstallFromAdb(
        params: PackageInstaller.SessionParams,
        packageName: String,
    ) {
        try {
            val flag = ShizukuSilentInstall.resolveInstallFromAdbFlag(PackageManager::class.java)
            if (!ShizukuSilentInstall.applyInstallFromAdbFlag(params, flag)) {
                Log.w(TAG, "adb install flag not applied for $packageName [setter-absent]")
            }
        } catch (e: Exception) {
            Log.w(TAG, "adb install flag skipped for $packageName [${e.javaClass.simpleName}]")
        }
    }

    /**
     * Installer identity for privileged sessions. When Shizuku runs as
     * shell (UID 2000, the non-root case), the session must be attributed
     * to the shell package — exactly what ackpine does — or
     * PackageManagerService treats the session as an ordinary app install
     * and posts the system confirmation dialog (the production symptom
     * that survived the adb-install flag). On root/Sui (UID 0) our own
     * package is correct. Unknown UID (binder died mid-read) fails closed
     * to our package name: the dialog fallback + read-back verification
     * decide the outcome, never a mis-attributed session.
     */
    fun installerPackageName(appPackageName: String, shizukuUid: Int?): String =
        if (shizukuUid != null && shizukuUid != 0) SHELL_INSTALLER_PACKAGE else appPackageName

    /** Shell package name used as installer identity on non-root Shizuku. */
    const val SHELL_INSTALLER_PACKAGE = "com.android.shell"

    /** Shizuku peer UID, or null when the binder is dead. Root is 0. */
    fun peerUid(): Int? = try {
        Shizuku.getUid()
    } catch (_: Exception) {
        null
    }

    /**
     * Builds a `PackageInstaller` whose sessions commit with shell
     * privilege: the `package` system-service binder fetched through
     * Shizuku, wrapped so calls run as the Shizuku peer (shell/root),
     * unwrapped to `IPackageManager`, then its `getPackageInstaller()`
     * backing a `PackageInstaller` for our package on the current user.
     * Hidden-API access (`IPackageManager`, its nested Stub/Proxy, the
     * hidden `PackageInstaller` constructor) is opened with
     * HiddenApiBypass (Apache-2.0, version-pinned) — reflection only,
     * written originally for this call path. Throws on any edge (the
     * caller maps it to transport fallback).
     */
    private fun privilegedInstaller(app: Context): PackageInstaller {
        // Prefixes without a trailing ';' so nested Stub/Proxy classes
        // (e.g. IPackageManager$Stub$Proxy, which declares the invoked
        // methods) still match the exemption.
        HiddenApiBypass.addHiddenApiExemptions(
            "Landroid/content/pm/IPackageManager",
            "Landroid/content/pm/IPackageInstaller",
            "Landroid/content/pm/PackageInstaller",
            "Landroid/content/pm/PackageManager",
            "Landroid/content/pm/ParceledListSlice",
            "Landroid/content/Context",
        )
        val raw: IBinder = SystemServiceHelper.getSystemService("package")
            ?: throw IllegalStateException("no package service binder")
        val binder = ShizukuBinderWrapper(raw)
        val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val ipm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IllegalStateException("no IPackageManager")
        val ipi = ipm.javaClass.getMethod("getPackageInstaller").invoke(ipm)
            ?: throw IllegalStateException("no IPackageInstaller")
        val ipiClass = Class.forName("android.content.pm.IPackageInstaller")
        // Current user only (Context.getUserId is hidden platform API, so
        // reflective under the same exemption; work profiles are future
        // work). Throws on any edge — the caller maps it to transport
        // fallback, never to a wrong-user install.
        val userId = Context::class.java.getMethod("getUserId").invoke(app) as Int
        // Attribute the session to the shell peer (non-root) or to us
        // (root/Sui): the installer identity decides whether the system
        // trusts the commit silently. See installerPackageName.
        val installerName = installerPackageName(app.packageName, peerUid())
        return try {
            val ctor = PackageInstaller::class.java.getDeclaredConstructor(
                ipiClass,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            ctor.isAccessible = true
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) app.attributionTag else null
            ctor.newInstance(ipi, installerName, tag, userId) as PackageInstaller
        } catch (_: NoSuchMethodException) {
            // Pre-R (API < 30): no attribution-tag parameter.
            val ctor = PackageInstaller::class.java.getDeclaredConstructor(
                ipiClass,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            ctor.isAccessible = true
            ctor.newInstance(ipi, installerName, userId) as PackageInstaller
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
