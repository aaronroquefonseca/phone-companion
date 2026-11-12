package com.phonecompanion.app.notify

import androidx.work.OutOfQuotaPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Pins the FCM-wake request shape: expedited with a run-as-normal
 * fallback, so the silent wake really polls NOW.
 *
 * NOTE: `androidx.work:work-testing` is deliberately NOT a dependency
 * (no new test deps for this change), so instead of driving a
 * TestDriver this builds the real request via
 * [AppInstallWakeWorker.wakeRequest] and asserts on its WorkSpec
 * directly. The service-side branch itself (wake → enqueue, no render)
 * needs a device/emulator and is covered by the manual checklist, not
 * here.
 */
class AppInstallWakeWorkerTest {

    @Test
    fun `wake poll is expedited with non-expedited fallback`() {
        val request = AppInstallWakeWorker.wakeRequest()
        assertTrue(
            "wake poll must be expedited (instant delivery, not OEM-deferred)",
            request.workSpec.expedited,
        )
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            request.workSpec.outOfQuotaPolicy,
        )
    }

    @Test
    fun `wake poll failure then success polls twice total`() = runBlocking {
        var polls = 0
        val delays = mutableListOf<Long>()
        AppInstallWakeWorker.pollWithRetries(
            poll = {
                polls++
                if (polls == 1) throw IOException("transient blip")
            },
            sleeper = { delays.add(it) },
            logInfo = { },
        )
        assertEquals("blip then success must poll twice", 2, polls)
        assertEquals(
            "one exponential backoff sleep between the attempts",
            listOf(AppInstallWakeWorker.retryDelayMs(1)),
            delays,
        )
    }

    @Test
    fun `persistent wake failure polls exactly three times then gives up`() = runBlocking {
        var polls = 0
        val delays = mutableListOf<Long>()
        // Must not throw: the worker still succeeds the work and the
        // scheduled poll stays the backstop.
        AppInstallWakeWorker.pollWithRetries(
            poll = {
                polls++
                throw IOException("server down")
            },
            sleeper = { delays.add(it) },
            logInfo = { },
        )
        assertEquals(
            "persistent failure must stop after exactly MAX_POLL_ATTEMPTS",
            AppInstallWakeWorker.MAX_POLL_ATTEMPTS,
            polls,
        )
        assertEquals(
            "two exponential backoff sleeps across three attempts",
            listOf(
                AppInstallWakeWorker.retryDelayMs(1),
                AppInstallWakeWorker.retryDelayMs(2),
            ),
            delays,
        )
    }

    @Test
    fun `successful wake polls once with no delay`() = runBlocking {
        var polls = 0
        var sleeps = 0
        AppInstallWakeWorker.pollWithRetries(
            poll = { polls++ },
            sleeper = { sleeps++ },
            logInfo = { },
        )
        assertEquals("success must poll exactly once", 1, polls)
        assertEquals("success must never sleep", 0, sleeps)
    }

    @Test
    fun `wake retry rethrows cancellation without retrying`() = runBlocking {
        var polls = 0
        var sleeps = 0
        try {
            AppInstallWakeWorker.pollWithRetries(
                poll = {
                    polls++
                    throw CancellationException("worker stopped")
                },
                sleeper = { sleeps++ },
                logInfo = { },
            )
            fail("cancellation must propagate, never succeed silently")
        } catch (_: CancellationException) {
            // Expected: cancellation rethrows.
        }
        assertEquals("cancelled wake must poll exactly once", 1, polls)
        assertEquals("cancelled wake must never sleep", 0, sleeps)
    }

    @Test
    fun `retry backoff doubles from the enqueue base`() {
        assertEquals(
            "retry delays must double (exponential, same shape as the enqueue backoff)",
            2 * AppInstallWakeWorker.retryDelayMs(1),
            AppInstallWakeWorker.retryDelayMs(2),
        )
    }
}
