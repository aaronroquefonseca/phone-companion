package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class AppInstallTransferTest {

    @Test
    fun `small copy succeeds`() {
        val bytes = "hello-apk".toByteArray()
        val out = ByteArrayOutputStream()
        val outcome = AppInstallTransfer.copyCapped(
            ByteArrayInputStream(bytes),
            out,
            maxBytes = 100,
        )
        assertTrue(outcome is AppInstallTransfer.Outcome.Success)
        assertEquals(bytes.size.toLong(), (outcome as AppInstallTransfer.Outcome.Success).bytesCopied)
        assertEquals("hello-apk", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `budget cap enforced on an oversized source`() {
        // The cap is now the device's free space (see downloadBudget), so
        // the transfer still has to refuse a stream that exceeds whatever
        // budget it is given.
        val budget = 1L * 1024L * 1024L
        val oversized = object : InputStream() {
            private var remaining = budget + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
        }
        val out = ByteArrayOutputStream()
        val outcome = AppInstallTransfer.copyCapped(oversized, out, maxBytes = budget)
        assertTrue(outcome is AppInstallTransfer.Outcome.TooLarge)
    }

    @Test
    fun `cap enforced at a small bound too`() {
        val out = ByteArrayOutputStream()
        val outcome = AppInstallTransfer.copyCapped(
            ByteArrayInputStream(ByteArray(100)),
            out,
            maxBytes = 10,
        )
        assertTrue(outcome is AppInstallTransfer.Outcome.TooLarge)
    }

    @Test
    fun `exact cap size succeeds, one byte over fails`() {
        val atCap = AppInstallTransfer.copyCapped(
            ByteArrayInputStream(ByteArray(10)),
            ByteArrayOutputStream(),
            maxBytes = 10,
        )
        assertTrue(atCap is AppInstallTransfer.Outcome.Success)
        val overCap = AppInstallTransfer.copyCapped(
            ByteArrayInputStream(ByteArray(11)),
            ByteArrayOutputStream(),
            maxBytes = 10,
        )
        assertTrue(overCap is AppInstallTransfer.Outcome.TooLarge)
    }

    @Test
    fun `cancel before first byte yields Cancelled`() {
        val out = ByteArrayOutputStream()
        val outcome = AppInstallTransfer.copyCapped(
            ByteArrayInputStream("data".toByteArray()),
            out,
            maxBytes = 100,
            isCancelled = { true },
        )
        assertTrue(outcome is AppInstallTransfer.Outcome.Cancelled)
    }

    @Test
    fun `cancel mid-copy yields Cancelled`() {
        var reads = 0
        val slow = object : InputStream() {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads > 3) return -1
                b[off] = 1
                return 1
            }
            override fun read(): Int = 1
        }
        val out = ByteArrayOutputStream()
        val outcome = AppInstallTransfer.copyCapped(
            slow,
            out,
            maxBytes = 100,
            // Cancel after the second buffer fill.
            isCancelled = { reads >= 2 },
        )
        assertTrue(outcome is AppInstallTransfer.Outcome.Cancelled)
    }
}
