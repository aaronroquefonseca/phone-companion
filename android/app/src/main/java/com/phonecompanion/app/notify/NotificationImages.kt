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
 */
object NotificationImages {

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Hard cap on downloaded bytes (defense against huge attachments). */
    const val MAX_BYTES = 5 * 1024 * 1024

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
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
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
