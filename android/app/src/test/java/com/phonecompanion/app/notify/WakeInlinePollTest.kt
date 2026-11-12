package com.phonecompanion.app.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Wake-branch decision matrix for
 * [CompanionMessagingService.handleWakeInline] (pure collaborators, no
 * Android edge — the service wiring itself needs a device/emulator and
 * stays on the manual checklist).
 *
 * Pins: inline success skips the worker enqueue (the posted-set dedupes
 * the race if the worker runs anyway, so skipping only saves work);
 * inline failure and inline timeout BOTH fall back to the existing wake
 * worker enqueue; genuine coroutine teardown rethrows and enqueues
 * nothing extra; the inline budget is a named 15 s cap.
 */
class WakeInlinePollTest {

    @Test
    fun `inline timeout is fifteen seconds`() {
        assertEquals(15_000L, CompanionMessagingService.INLINE_POLL_TIMEOUT_MS)
    }

    @Test
    fun `inline success skips worker enqueue`() = runBlocking {
        var polled = 0
        var enqueued = 0
        CompanionMessagingService.handleWakeInline(
            inlinePoll = { polled++ },
            enqueueWakeWorker = { enqueued++ },
        )
        assertEquals(1, polled)
        assertEquals(0, enqueued)
    }

    @Test
    fun `inline failure enqueues worker backstop`() = runBlocking {
        var enqueued = 0
        CompanionMessagingService.handleWakeInline(
            inlinePoll = { throw IOException("network down") },
            enqueueWakeWorker = { enqueued++ },
        )
        assertEquals(1, enqueued)
    }

    @Test
    fun `inline timeout enqueues worker backstop`() = runBlocking {
        var enqueued = 0
        CompanionMessagingService.handleWakeInline(
            inlinePoll = { delay(5_000) },
            enqueueWakeWorker = { enqueued++ },
            timeoutMs = 50,
        )
        assertEquals(1, enqueued)
    }

    @Test
    fun `enqueue failure itself never throws`() = runBlocking {
        // The backstop enqueue is best-effort (WorkManager may throw on a
        // dying process): the branch must stay silent either way.
        CompanionMessagingService.handleWakeInline(
            inlinePoll = { throw IOException("network down") },
            enqueueWakeWorker = { throw IllegalStateException("work manager gone") },
        )
    }

    @Test
    fun `cancellation rethrows and enqueues nothing extra`() = runBlocking {
        var enqueued = 0
        try {
            CompanionMessagingService.handleWakeInline(
                inlinePoll = { throw CancellationException("service teardown") },
                enqueueWakeWorker = { enqueued++ },
            )
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message!!.contains("teardown"))
        }
        assertEquals(0, enqueued)
    }
}
