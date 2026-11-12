package com.phonecompanion.app.notify

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Executes one fast-link command on the device and returns the bytes the
 * server will hand straight to the operator: a JPEG/PNG for a screenshot,
 * the file itself for a pull, text for logs, JSON for a shell result.
 *
 * The point is the payload, not the protocol: unlike the outbox channel
 * there is no 64 KB text cap, so images never get base64'd or sliced.
 */
object LinkOps {

    private const val TAG = "LinkOps"
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024

    class Result(val contentType: String, val bytes: ByteArray, val status: Int = 200)

    /** Set while the fast link is up so commands reuse one Shizuku binder. */
    @Volatile
    var session: ShizukuRemoteShell.Session? = null

    suspend fun execute(app: Context, kind: String, args: JSONObject): Result = when (kind) {
        "screenshot" -> screenshot(app, args)
        "file" -> file(app, args)
        "logs" -> logs(app, args)
        "shell" -> shell(app, args)
        else -> error("unknown kind")
    }

    private suspend fun screenshot(app: Context, args: JSONObject): Result {
        val dir = linkDir(app) ?: error("no external dir")
        if (args.optBoolean("full", false)) {
            val png = File(dir, "shot.png")
            run(app, "screencap -p '${shellPath(png)}'")
            val bytes = png.readBytes()
            png.delete()
            return Result("image/png", bytes)
        }
        val targetWidth = args.optInt("width", 540).coerceIn(120, 2160)
        val quality = args.optInt("quality", 60).coerceIn(20, 95)
        // Fast path: a live projection captures in ~30 ms against
        // screencap's ~250 ms.
        if (ScreenProjection.isReady) {
            ScreenProjection.capture(targetWidth, quality)?.let {
                return Result("image/jpeg", it)
            }
        }
        val raw = File(dir, "shot.raw")
        // Raw capture: no PNG encode on the device (550 ms vs 270 ms), the
        // downscale happens here where it costs a few milliseconds.
        run(app, "screencap '${shellPath(raw)}'")
        val bytes = raw.readBytes()
        raw.delete()
        if (bytes.size < 12) error("short capture")
        val hdr = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val w = hdr.int
        val h = hdr.int
        if (w <= 0 || h <= 0 || w > 4320 || h > 5000) error("bad capture $w x $h")
        val step = maxOf(1, (w + targetWidth - 1) / targetWidth)
        val outW = w / step
        val outH = h / step
        val pixels = IntArray(outW * outH)
        var i = 12
        for (y in 0 until outH) {
            var row = i + y * step * w * 4
            for (x in 0 until outW) {
                val p = row + x * step * 4
                if (p + 3 >= bytes.size) break
                val r = bytes[p].toInt() and 0xFF
                val g = bytes[p + 1].toInt() and 0xFF
                val b = bytes[p + 2].toInt() and 0xFF
                pixels[y * outW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream(96 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return Result("image/jpeg", out.toByteArray())
    }

    private suspend fun file(app: Context, args: JSONObject): Result {
        val path = args.optString("path").trim()
        require(path.startsWith("/")) { "absolute path required" }
        val dir = linkDir(app) ?: error("no external dir")
        val dest = File(dir, "pull.bin")
        dest.delete()
        run(app, "cp -f '${shellPath(File(path))}' '${shellPath(dest)}'")
        if (!dest.exists()) error("copy failed")
        require(dest.length() <= MAX_FILE_BYTES) { "file too large" }
        // Images can come back downscaled: a 12 MP photo is 1.5-4 MB and
        // the link is ~13 Mbit/s, so "as stored" is never under a second.
        // width=0 (default) keeps the original bytes untouched.
        val width = args.optInt("width", 0)
        val bytes = if (width > 0 && isImage(path)) {
            val quality = args.optInt("quality", 80).coerceIn(30, 95)
            shrinkImage(dest, width, quality) ?: dest.readBytes()
        } else {
            dest.readBytes()
        }
        dest.delete()
        return Result(if (width > 0 && isImage(path)) "image/jpeg" else contentTypeFor(path), bytes)
    }

    private fun isImage(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

    /** Decode with a power-of-two sample, then scale to [targetWidth]. */
    private fun shrinkImage(src: File, targetWidth: Int, quality: Int): ByteArray? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(src.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                // RGB_565 decodes ~30% faster and is fine for a preview;
                // the original is always available with width=0.
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = android.graphics.BitmapFactory.decodeFile(src.absolutePath, opts) ?: return null
            val scaled = if (decoded.width > targetWidth) {
                val h = (decoded.height.toLong() * targetWidth / decoded.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetWidth, h, false).also { decoded.recycle() }
            } else {
                decoded
            }
            val out = ByteArrayOutputStream(128 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            scaled.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun logs(app: Context, args: JSONObject): Result {
        val lines = args.optInt("lines", 200).coerceIn(1, 20000)
        val filter = args.optString("filter").trim()
        val buffer = args.optString("buffer", "main").ifBlank { "main" }
        // -b main is ~3x faster than the default (all buffers): 360 ms vs
        // 1.1 s for 200 lines, measured on this device.
        val cmd = buildString {
            append("logcat -d -b ").append(buffer).append(" -t ").append(lines)
            if (filter.isNotEmpty()) append(' ').append(filter)
        }
        val r = shellResult(app, cmd, 30000)
        val obj = JSONObject(r)
        val text = obj.optString("stdout") + obj.optString("stderr")
        return Result("text/plain; charset=utf-8", text.toByteArray())
    }

    private suspend fun shell(app: Context, args: JSONObject): Result {
        val cmd = args.optString("command")
        require(cmd.isNotBlank()) { "empty command" }
        val timeout = args.optInt("timeout_ms", 30000)
        return Result("application/json", shellResult(app, cmd, timeout).toByteArray())
    }

    private suspend fun shellResult(app: Context, cmd: String, timeoutMs: Int): String {
        val r = session?.run(cmd, timeoutMs) ?: ShizukuRemoteShell.runOnce(app, cmd, timeoutMs)
        return when (r) {
            is ShizukuRemoteShell.Result.Done -> JSONObject()
                .put("exit_code", r.outcome.exitCode)
                .put("stdout", r.outcome.stdout)
                .put("stderr", r.outcome.stderr)
                .put("truncated", r.outcome.truncated)
                .toString()
            is ShizukuRemoteShell.Result.NotGranted ->
                throw IllegalStateException("shizuku_not_granted")
            is ShizukuRemoteShell.Result.TransportFailure ->
                throw IllegalStateException("shell_transport_failure")
        }
    }

    /** Runs one shell command, ignoring its output (screencap/cp only). */
    private suspend fun run(app: Context, cmd: String) {
        val r = session?.run(cmd, 60000) ?: ShizukuRemoteShell.runOnce(app, cmd, 60000)
        when (r) {
            is ShizukuRemoteShell.Result.Done -> {
                if (r.outcome.exitCode != 0) {
                    Log.w(TAG, "op command exit=${r.outcome.exitCode} ${r.outcome.stderr.take(120)}")
                    error("command failed (${r.outcome.exitCode})")
                }
            }
            is ShizukuRemoteShell.Result.NotGranted -> error("shizuku_not_granted")
            is ShizukuRemoteShell.Result.TransportFailure -> error("shell_transport_failure")
        }
    }

    private fun linkDir(app: Context): File? =
        app.getExternalFilesDir("link")?.also { it.mkdirs() }

    private fun shellPath(f: File): String = f.absolutePath.replace("'", "'\\''")

    private fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "txt", "log" -> "text/plain; charset=utf-8"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}
