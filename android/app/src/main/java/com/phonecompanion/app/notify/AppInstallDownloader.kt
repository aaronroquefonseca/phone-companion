package com.phonecompanion.app.notify

import android.os.SystemClock
import android.util.Log
import com.phonecompanion.app.data.ApiClient
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.URI

/**
 * HTTPS APK download into the app cache dir.
 *
 * Android side of the flow; the cap/cancel byte logic itself lives in the
 * pure, unit-tested [AppInstallTransfer]. Uses the shared OkHttp stack
 * ([ApiClient.downloadClient]: same timeouts as the API client, but no
 * auth interceptor and no logging interceptor — APK URLs may be signed
 * third-party URLs and the device Bearer token must never be sent there,
 * and signed query tokens must never reach logcat).
 *
 * Redirects are followed manually, HTTPS-only: any hop leaving HTTPS (or
 * a missing/relative Location, or more than [MAX_REDIRECTS] hops) aborts
 * with [Result.Failed]. A usable Content-Length is honored up-front (over
 * the cap → [Result.TooLarge] with zero bytes fetched); the stream copy
 * re-enforces the cap for lying/chunked servers. Runs on the caller's
 * thread — call from a background coroutine, never the main thread.
 * Logging discipline: package names only, never URLs or hashes.
 */
object AppInstallDownloader {

    const val TAG = "AppInstallDownload"

    /** Max redirect hops before aborting (named: was a bare `5`). */
    const val MAX_REDIRECTS = 5

    /**
     * Progress notifications are throttled to at most one per window —
     * rapid buffer fills must not re-notify hundreds of times.
     */
    const val PROGRESS_THROTTLE_MS = 200L

    /** Terminal outcome of the download phase (no install attempted yet). */
    sealed interface Result {
        /** File is complete on disk (still unverified — verify before install). */
        data class Done(val bytes: Long) : Result

        /** Server-declared or actual size exceeded the 100 MB cap. */
        data object TooLarge : Result

        /** Cancel observed: partial file already deleted by the caller path. */
        data object Cancelled : Result

        /** Network/IO failure: caller errors, no retry, no install. */
        data object Failed : Result
    }

    fun download(
        payload: AppInstallPayload,
        dest: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result {
        try {
            dest.parentFile?.mkdirs()
            // Stale partials from an abandoned earlier attempt must never be
            // trusted or appended to: start from zero bytes.
            if (dest.exists()) dest.delete()

            val client = ApiClient.downloadClient().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            var url = payload.apkUrl
            var hops = 0
            while (true) {
                val request = okhttp3.Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                response.use { res ->
                    val code = res.code
                    if (code in 301..308 && code != 304) {
                        if (hops >= MAX_REDIRECTS) {
                            Log.w(TAG, "app_install too many redirects for ${payload.packageName}")
                            return Result.Failed
                        }
                        val location = res.header("Location")?.trim().orEmpty()
                        val resolved = resolveUrl(url, location)
                        if (!AppInstallPayload.isValidApkUrl(resolved)) {
                            Log.w(TAG, "app_install redirect left https for ${payload.packageName}")
                            return Result.Failed
                        }
                        url = resolved
                        hops++
                        return@use
                    }
                    if (code !in 200..299) {
                        Log.w(TAG, "app_install http $code for ${payload.packageName}")
                        return Result.Failed
                    }
                    val declared = res.header("Content-Length")?.toLongOrNull()
                    val budget = AppInstallPayload.downloadBudget(dest.parentFile)
                    if (declared != null && declared > budget) {
                        Log.w(TAG, "app_install over cap for ${payload.packageName}")
                        return Result.TooLarge
                    }
                    val body = res.body ?: return Result.Failed
                    val outcome = body.byteStream().use { input ->
                        FileOutputStream(dest).use { raw ->
                            val counting = CountingOutputStream(raw, onBytes = { bytesRead ->
                                onProgress(bytesRead, declared?.takeIf { it >= 0 })
                            })
                            AppInstallTransfer.copyCapped(
                                input = input,
                                output = counting,
                                maxBytes = budget,
                                isCancelled = { AppInstallSessions.isCancelled(payload.pushId) },
                            )
                        }
                    }
                    return when (outcome) {
                        is AppInstallTransfer.Outcome.Success -> Result.Done(outcome.bytesCopied)
                        is AppInstallTransfer.Outcome.TooLarge -> {
                            dest.delete()
                            Log.w(TAG, "app_install over cap for ${payload.packageName}")
                            Result.TooLarge
                        }
                        is AppInstallTransfer.Outcome.Cancelled -> {
                            dest.delete()
                            Log.w(TAG, "app_install download cancelled for ${payload.packageName}")
                            Result.Cancelled
                        }
                        is AppInstallTransfer.Outcome.IoError -> {
                            dest.delete()
                            Log.w(TAG, "app_install download failed for ${payload.packageName}")
                            Result.Failed
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Class name only — URLs and hashes never reach logcat here.
            runCatching { dest.delete() }
            Log.w(TAG, "app_install download failed for ${payload.packageName} [${e.javaClass.simpleName}]")
            if (AppInstallSessions.isCancelled(payload.pushId)) return Result.Cancelled
            return Result.Failed
        }
    }

    /**
     * Resolves a redirect Location against [base] via [URI]. Absolute
     * HTTPS URLs pass through; absolute-path locations inherit the base
     * origin; anything else (protocol-relative, bare relative, non-https,
     * unparseable) yields "" so the caller's HTTPS check rejects it.
     */
    internal fun resolveUrl(base: String, location: String): String {
        val loc = location.trim()
        // Protocol-relative ("//host/path") inherits the scheme — never
        // trust it blindly; reject outright rather than resolving.
        if (loc.isEmpty() || loc.startsWith("//")) return ""
        return try {
            if (loc.regionMatches(0, "https://", 0, "https://".length, ignoreCase = true)) {
                if (AppInstallPayload.isValidApkUrl(loc)) loc else ""
            } else if (loc.startsWith("/")) {
                val b = URI(base)
                if (!b.scheme.equals("https", ignoreCase = true) || b.authority.isNullOrEmpty()) {
                    ""
                } else {
                    val resolved = URI("https", b.authority, loc, null, null).toString()
                    if (AppInstallPayload.isValidApkUrl(resolved)) resolved else ""
                }
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Throttled progress tap: forwards byte counts at most once per
     * [PROGRESS_THROTTLE_MS] (plus every write's final total implicitly
     * via the last call the downloader makes after completion).
     *
     * Internal with an injectable [clockMs] so both write overloads and
     * the throttle window are unit-testable on the JVM. Both overloads
     * count — a single-byte write path that skipped the total would
     * under-report progress.
     */
    internal class CountingOutputStream(
        out: OutputStream,
        private val onBytes: (Long) -> Unit,
        private val clockMs: () -> Long = { SystemClock.uptimeMillis() },
    ) : FilterOutputStream(out) {
        internal var total = 0L
        private var lastEmitMs = 0L

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            total += len
            maybeEmit()
        }

        override fun write(b: Int) {
            out.write(b)
            total += 1
            maybeEmit()
        }

        private fun maybeEmit() {
            val now = clockMs()
            if (now - lastEmitMs >= PROGRESS_THROTTLE_MS) {
                lastEmitMs = now
                runCatching { onBytes(total) }
            }
        }
    }
}
