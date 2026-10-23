package com.phonecompanion.app.notify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * HTTPS image download for notification attachments (contract `image_url`).
 *
 * Fail-closed by design: non-HTTPS URLs, redirects leaving HTTPS,
 * non-2xx responses, oversized bodies, and undecodable bytes all yield
 * null, and the caller falls back to text rendering. No exception ever
 * escapes — a bad attachment must never crash or block a notification.
 *
 * Decode is two-pass and bounded: pass one reads dimensions only
 * ([BitmapFactory.Options.inJustDecodeBounds]), images whose pixel count
 * exceeds [MAX_PIXELS] are rejected before any pixel buffer exists, and
 * pass two downsamples ([inSampleSize]) so the decoded bitmap's longest
 * side never exceeds [MAX_DIMENSION_PX]. Everything is guarded by a
 * `catch (Throwable)` — including [OutOfMemoryError] — because a hostile
 * attachment must not kill the FCM service.
 */
object NotificationImages {

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Hard cap on downloaded bytes (defense against huge attachments). */
    const val MAX_BYTES = 5 * 1024 * 1024

    /** Longest decoded side in px; larger images are downsampled to fit. */
    const val MAX_DIMENSION_PX = 1024

    /** Images with more pixels than this are rejected before decoding. */
    const val MAX_PIXELS = 4_000_000L

    fun download(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            if (!url.protocol.equals("https", ignoreCase = true)) return null
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
            }
            try {
                conn.connect()
                // Re-validate after redirects: must still be HTTPS.
                if (!conn.url.protocol.equals("https", ignoreCase = true)) return null
                if (conn.responseCode !in 200..299) return null
                if (conn.contentLength > MAX_BYTES) return null
                val bytes = conn.inputStream.use { it.readCapped(MAX_BYTES + 1) }
                    ?: return null
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
                decodeBounded(bytes)
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Two-pass bounded decode: bounds first, pixel-count gate, then a
     * downsampled decode. Returns null for anything undecodable or over
     * budget. Never throws.
     */
    fun decodeBounded(bytes: ByteArray): Bitmap? {
        try {
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            if (width.toLong() * height.toLong() > MAX_PIXELS) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(width, height) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Pure downsample math: the smallest power-of-two sample size whose
     * application brings both sides within [MAX_DIMENSION_PX]. Exported
     * for unit tests; byte-identical wherever it runs.
     */
    fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > MAX_DIMENSION_PX || height / sampleSize > MAX_DIMENSION_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun InputStream.readCapped(cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
