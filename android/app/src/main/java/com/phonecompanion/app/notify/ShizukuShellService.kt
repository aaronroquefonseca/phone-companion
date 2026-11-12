package com.phonecompanion.app.notify

import android.content.Context
import android.util.Log
import com.phonecompanion.app.shell.IRemoteShell
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Shizuku user-service side of the remote shell channel.
 *
 * Runs in the Shizuku-provided shell/root process (UID 2000 on ADB, 0 on
 * root/Sui), so it executes with exactly `adb shell` privilege. The ONLY
 * operation is one bounded `/system/bin/sh -c <command>` via ProcessBuilder
 * — no `Shizuku.newProcess` (deprecated), no context APIs (this is not a
 * valid app process), no file retention.
 *
 * Output is base64-packed so arbitrary bytes and delimiters never corrupt
 * the result: `<exit>\n<truncated 0|1>\n<b64 stdout>\n<b64 stderr>`.
 * Each stream is capped at [MAX_STREAM_BYTES].
 */
class ShizukuShellService : IRemoteShell.Stub {

    constructor() : super()

    @Suppress("unused", "UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun run(command: String, timeoutMs: Int): String {
        if (command.isBlank()) return pack(-1, false, "", "empty command")
        val effectiveTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        var truncated = false
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val out = readCapped(proc.inputStream)
            val err = readCapped(proc.errorStream)
            truncated = out.truncated || err.truncated
            val exited = try {
                proc.waitFor(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                false
            }
            if (!exited) {
                runCatching { proc.destroyForcibly() }
                pack(TIMEOUT_EXIT, true, out.text, "timed out after ${effectiveTimeout}ms")
            } else {
                pack(proc.exitValue(), truncated, out.text, err.text)
            }
        } catch (e: Exception) {
            pack(-1, false, "", e.javaClass.simpleName)
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    private data class Capped(val text: String, val truncated: Boolean)

    private fun readCapped(stream: java.io.InputStream): Capped {
        return try {
            stream.use { input ->
                val buf = ByteArray(MAX_STREAM_BYTES)
                var read = 0
                while (read < MAX_STREAM_BYTES) {
                    val n = input.read(buf, read, MAX_STREAM_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                // One extra byte tells us whether the stream continued.
                val more = input.read() >= 0
                Capped(String(buf, 0, read, Charsets.UTF_8), more)
            }
        } catch (_: Exception) {
            Capped("", false)
        }
    }

    private fun pack(exit: Int, truncated: Boolean, stdout: String, stderr: String): String {
        val enc = Base64.getEncoder()
        return buildString {
            append(exit).append('\n')
            append(if (truncated) 1 else 0).append('\n')
            append(enc.encodeToString(stdout.toByteArray(Charsets.UTF_8))).append('\n')
            append(enc.encodeToString(stderr.toByteArray(Charsets.UTF_8)))
        }
    }

    companion object {
        private const val TAG = "ShizukuShellSvc"
        internal const val MAX_STREAM_BYTES = 64 * 1024
        internal const val MIN_TIMEOUT_MS = 1_000
        internal const val MAX_TIMEOUT_MS = 120_000
        internal const val TIMEOUT_EXIT = 124
    }
}
