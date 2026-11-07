package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Covers redirect resolution (manual HTTPS-only chain) and the throttled
 * counting stream: both write overloads count, emissions respect the
 * throttle window.
 */
class AppInstallDownloaderTest {

    @Test
    fun `absolute https locations pass through`() {
        assertEquals(
            "https://cdn.example.com/a.apk",
            AppInstallDownloader.resolveUrl("https://example.com/a.apk", "https://cdn.example.com/a.apk"),
        )
    }

    @Test
    fun `absolute path locations inherit the base origin`() {
        assertEquals(
            "https://example.com/b.apk",
            AppInstallDownloader.resolveUrl("https://example.com/a.apk", "/b.apk"),
        )
    }

    @Test
    fun `redirects leaving https are rejected`() {
        val base = "https://example.com/a.apk"
        assertEquals("", AppInstallDownloader.resolveUrl(base, "http://example.com/b.apk"))
        assertEquals("", AppInstallDownloader.resolveUrl(base, "ftp://example.com/b.apk"))
        assertEquals("", AppInstallDownloader.resolveUrl(base, "//example.com/b.apk"))
        // Bare relative locations are rejected (never resolved against base).
        assertEquals("", AppInstallDownloader.resolveUrl(base, "b.apk"))
        assertEquals("", AppInstallDownloader.resolveUrl(base, "../b.apk"))
        assertEquals("", AppInstallDownloader.resolveUrl(base, ""))
        assertEquals("", AppInstallDownloader.resolveUrl(base, "   "))
        // Non-https base can never yield an https resolution.
        assertEquals("", AppInstallDownloader.resolveUrl("http://example.com/a.apk", "/b.apk"))
    }

    @Test
    fun `both write overloads count bytes`() {
        var now = 1_000_000L // production clock is uptime: first write emits
        var last = -1L
        val counting = AppInstallDownloader.CountingOutputStream(
            ByteArrayOutputStream(),
            onBytes = { last = it },
            clockMs = { now },
        )
        counting.write("hello".toByteArray(), 0, 5)
        assertEquals(5L, counting.total)
        assertEquals(5L, last)
        // Single-byte path counts too (the old override skipped the total).
        now += AppInstallDownloader.PROGRESS_THROTTLE_MS + 1
        counting.write(42)
        assertEquals(6L, counting.total)
        assertEquals(6L, last)
    }

    @Test
    fun `emissions respect the throttle window`() {
        var now = 1_000_000L // production clock is uptime: first write emits
        val emissions = mutableListOf<Long>()
        val counting = AppInstallDownloader.CountingOutputStream(
            ByteArrayOutputStream(),
            onBytes = { emissions += it },
            clockMs = { now },
        )
        counting.write(ByteArray(10), 0, 10)
        assertEquals(listOf(10L), emissions)
        // Rapid fills inside the window do not re-emit (but still count).
        now += 1
        counting.write(ByteArray(10), 0, 10)
        counting.write(1)
        assertEquals(listOf(10L), emissions)
        assertEquals(21L, counting.total)
        // Past the window the accumulated total emits.
        now += AppInstallDownloader.PROGRESS_THROTTLE_MS
        counting.write(ByteArray(5), 0, 5)
        assertEquals(listOf(10L, 26L), emissions)
        assertEquals(26L, counting.total)
    }

    @Test
    fun `redirect hop budget is a named constant`() {
        assertTrue(AppInstallDownloader.MAX_REDIRECTS > 0)
        assertEquals(5, AppInstallDownloader.MAX_REDIRECTS)
    }
}
