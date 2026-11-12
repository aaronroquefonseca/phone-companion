package com.phonecompanion.app.notify

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.phonecompanion.app.shell.IRemoteShell
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Android edge of the operator remote-shell channel.
 *
 * Binds [ShizukuShellService] (a Shizuku UserService running as the shell
 * peer), runs ONE bounded command, destroys the shell process, and
 * unbinds. Every transport edge (no binder, bind timeout, death mid-call)
 * returns [Outcome.TransportFailure]; the poll layer reports that back to
 * the server instead of retrying behind the operator's back.
 *
 * This is powerful by design: a granted Shizuku binder means the operator
 * account can run anything `adb shell` can. It is only reachable through
 * the authenticated outbox (operator credential to queue, device
 * credential to report), and the app warns the user before offering the
 * grant.
 */
object ShizukuRemoteShell {

    private const val TAG = "ShizukuRemoteShell"

    /** Stable tag (class names shift under obfuscation) + service version. */
    private const val SERVICE_TAG = "pc-remote-shell"
    private const val SERVICE_VERSION = 1

    internal const val BIND_TIMEOUT_MS = 15_000L

    /** One command outcome, already decoded from the wire pack. */
    data class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
    )

    sealed interface Result {
        data class Done(val outcome: Outcome) : Result
        data object TransportFailure : Result
        data object NotGranted : Result
    }

    /**
     * Executes one command. Call from a background coroutine.
     * Never throws for transport edges; cancellation is rethrown.
     */
    suspend fun runOnce(
        appContext: Context,
        command: String,
        timeoutMs: Int,
        bindTimeoutMs: Long = BIND_TIMEOUT_MS,
    ): Result {
        // destroy() exits the previous user-service process, and Shizuku can
        // still be reaping it when the next command binds the same tag. That
        // surfaces as a transport failure *before* anything ran, so retrying
        // is safe; a failure after the command was sent never is (at-most-
        // once), and attempt() distinguishes the two.
        repeat(2) { attempt ->
            when (val r = attempt(appContext, command, timeoutMs, bindTimeoutMs)) {
                Attempt.Retryable -> if (attempt == 0) delay(250)
                is Attempt.Done -> return r.result
            }
        }
        return Result.TransportFailure
    }

    private sealed interface Attempt {
        /** Failed at bind/call time, before the command could have run. */
        data object Retryable : Attempt
        data class Done(val result: Result) : Attempt
    }

    private suspend fun attempt(
        appContext: Context,
        command: String,
        timeoutMs: Int,
        bindTimeoutMs: Long,
    ): Attempt = withContext(Dispatchers.IO) {
        val app = appContext.applicationContext
        if (ShizukuPrivilegedInstall.statusNow() != ShizukuSilentInstall.Status.AVAILABLE_GRANTED) {
            return@withContext Attempt.Done(Result.NotGranted)
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ShizukuShellService::class.java.name),
        ).daemon(false)
            .processNameSuffix("remote-shell")
            .version(SERVICE_VERSION)
            .tag(SERVICE_TAG)
        val connected = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && !connected.isCompleted) connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("shell disconnected"))
                }
            }
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (_: Exception) {
            return@withContext Attempt.Retryable
        }
        val binder = try {
            withTimeoutOrNull(bindTimeoutMs) { connected.await() }
        } catch (_: Exception) {
            null
        }
        if (binder == null) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            return@withContext Attempt.Retryable
        }
        val service = IRemoteShell.Stub.asInterface(binder)
        val raw = try {
            service.run(command, timeoutMs)
        } catch (e: Exception) {
            // The command may have run before the call died: at-most-once
            // forbids a retry here.
            Log.i(TAG, "shell call failed [${e.javaClass.simpleName}]")
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            return@withContext Attempt.Done(Result.TransportFailure)
        }
        runCatching { service.destroy() }
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        parsePack(raw)?.let { Attempt.Done(Result.Done(it)) } ?: Attempt.Done(Result.TransportFailure)
    }

    /**
     * Parses the service's `<exit>\n<trunc>\n<b64 out>\n<b64 err>` pack.
     * Null on any shape problem (treated as a transport failure).
     */
    internal fun parsePack(raw: String?): Outcome? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split('\n', limit = 4)
        if (parts.size != 4) return null
        val exit = parts[0].toIntOrNull() ?: return null
        val truncated = when (parts[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return try {
            val dec = Base64.getDecoder()
            Outcome(
                exitCode = exit,
                stdout = String(dec.decode(parts[2]), Charsets.UTF_8),
                stderr = String(dec.decode(parts[3]), Charsets.UTF_8),
                truncated = truncated,
            )
        } catch (_: Exception) {
            null
        }
    }
}
