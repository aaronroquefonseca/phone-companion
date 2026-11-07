package com.phonecompanion.app.notify

import java.io.InputStream
import java.io.OutputStream

/**
 * Bounded, cancellable byte transfer for APK downloads.
 *
 * Pure JVM (java.io only, zero Android imports): the 100 MB hard cap and
 * the cancel path are unit-testable with fake streams — no network, no
 * emulator. The network layer ([AppInstallDownloader], Android side) feeds
 * the response stream in and checks [isCancelled] between buffer fills;
 * this core enforces the cap even when the server lies about
 * Content-Length (chunked or dishonest length).
 */
object AppInstallTransfer {

    /** Outcome of a capped copy. Exactly one terminal state per transfer. */
    sealed interface Outcome {
        /** All bytes copied within budget; [bytesCopied] is the total. */
        data class Success(val bytesCopied: Long) : Outcome

        /** Source exceeded [maxBytes]: caller must abort + error, no install. */
        data object TooLarge : Outcome

        /** Cancel observed mid-copy: caller must delete the partial file. */
        data object Cancelled : Outcome

        /** Read/write failure: caller errors, no retry, no install. */
        data class IoError(val message: String) : Outcome
    }

    /**
     * Copies [input] to [output] capped at [maxBytes], polling
     * [isCancelled] after each buffer fill. Returns [Outcome.Cancelled]
     * without error when cancellation is observed (including when already
     * cancelled before the first byte). Never throws.
     */
    fun copyCapped(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = AppInstallPayload.MAX_APK_BYTES,
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        return try {
            if (isCancelled()) return Outcome.Cancelled
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return Outcome.TooLarge
                output.write(buf, 0, n)
                if (isCancelled()) return Outcome.Cancelled
            }
            output.flush()
            Outcome.Success(total)
        } catch (e: Exception) {
            Outcome.IoError(e.javaClass.simpleName)
        }
    }
}
