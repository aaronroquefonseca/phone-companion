package com.phonecompanion.app.notify

import android.content.Context
import android.util.Log
import com.phonecompanion.app.shizuku.ISilentInstallService
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService side of silent installs.
 *
 * Runs in the Shizuku-provided shell/root process (UID 2000 when Shizuku
 * runs on ADB, 0 on root/Sui) — NOT in the app process. The ONLY privileged
 * operation is `pm install -r <staged-apk>` via standard Java process APIs
 * (no `Shizuku.newProcess`: deprecated since 13.1.1, slated for removal).
 * Shell `pm install` needs no unknown-sources user grant (like `adb
 * install`), so the flow skips the grant gate on the silent path — and no
 * system confirmation dialog ever appears there.
 *
 * Bound per install via `Shizuku.bindUserService` ([ShizukuShell]) and
 * destroyed right after: [destroy] exits the shell process so nothing
 * lingers. This process is not a valid app process — no Context APIs, no
 * receivers, no content resolvers; plain `ProcessBuilder` only.
 *
 * Logging discipline: exit codes only — never APK paths or pm output
 * (the app side logs package + exit code; the output rides into the
 * failure notification as secondary detail only).
 */
class ShizukuSilentInstallService : ISilentInstallService.Stub {

    /** Constructor used by older Shizuku servers. */
    constructor() : super()

    /**
     * Constructor Shizuku v13 tries first. The Context is NOT a normal
     * app context (this is not a valid app process) — never touch it.
     */
    @Suppress("unused", "UNUSED_PARAMETER")
    constructor(context: Context) : super()

    /**
     * Runs `pm install -r` on the staged APK and returns the packed
     * `"exit:<code>\n<output>"` string ([ShizukuSilentInstall] parses it;
     * the raw pm output feeds the existing cause-aware failure mapping).
     * Never throws — every edge packs as a Finished failure or reject.
     */
    override fun installApk(apkPath: String?): String {
        val path = apkPath.orEmpty()
        if (!ShizukuSilentInstall.isStagedApkPath(path)) {
            Log.w(TAG, "installApk rejected path shape")
            return pack(EXIT_REJECTED, "")
        }
        return try {
            val proc = ProcessBuilder("pm", "install", "-r", path)
                .redirectErrorStream(true)
                .start()
            val output = try {
                proc.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }.take(ShizukuSilentInstall.MAX_SHELL_OUTPUT_CHARS)
            val exited = try {
                proc.waitFor(PM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: Exception) {
                false
            }
            if (!exited) {
                runCatching { proc.destroyForcibly() }
                Log.w(TAG, "installApk pm timed out")
                return pack(EXIT_REJECTED, "")
            }
            val code = try {
                proc.exitValue()
            } catch (_: Exception) {
                EXIT_REJECTED
            }
            Log.i(TAG, "installApk exit=$code")
            pack(code, output)
        } catch (_: Exception) {
            pack(EXIT_REJECTED, "")
        }
    }

    /**
     * Reserved Shizuku destroy (aidl transaction 16777114, wire
     * 16777115): the client calls this after every install; exiting here
     * guarantees the shell process never lingers.
     */
    override fun destroy() {
        System.exit(0)
    }

    private fun pack(exitCode: Int, output: String): String = "exit:$exitCode\n$output"

    companion object {
        private const val TAG = "ShizukuInstallSvc"

        /** pm budget: local installs finish in seconds; fail closed above. */
        internal const val PM_TIMEOUT_SECONDS = 120L

        /** Internal reject (bad path, spawn failure, timeout). */
        internal const val EXIT_REJECTED = -1
    }
}
