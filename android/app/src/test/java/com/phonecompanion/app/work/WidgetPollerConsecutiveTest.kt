package com.phonecompanion.app.work

import com.phonecompanion.app.data.WidgetEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins the field-bug fix: a single 401/403 must NOT wipe credentials or
 * the widget cache. The wipe runs only after two consecutive auth
 * failures; success resets the streak; non-auth failures never increment
 * it and keep the last cache; every poll persists a status and logs the
 * HTTP code (no tokens/bodies).
 */
class WidgetPollerConsecutiveTest {

    private class FakeDeps(
        var outcome: WidgetPoller.PollFetchOutcome =
            WidgetPoller.PollFetchOutcome.TransportFailure,
        var failSave: Boolean = false,
    ) : WidgetPoller.PollCollaborators {
        val savedBatches = mutableListOf<List<WidgetEnvelope>>()
        var clearCredentialsCalls = 0
        var clearCacheCalls = 0
        var pushCalls = 0
        var fetchCalls = 0

        override suspend fun fetch(): WidgetPoller.PollFetchOutcome {
            fetchCalls++
            return outcome
        }

        override suspend fun saveWidgets(widgets: List<WidgetEnvelope>): Int {
            if (failSave) throw IOException("disk full")
            savedBatches += widgets
            return widgets.size
        }

        override suspend fun clearCache() {
            clearCacheCalls++
        }

        override suspend fun clearCredentials() {
            clearCredentialsCalls++
        }

        override suspend fun pushFailClosed() {
            pushCalls++
        }
    }

    private fun runPoll(
        deps: FakeDeps,
        state: PollStateStore,
        hasCredentials: Boolean = true,
        nowMs: Long = 1_700_000_000_000L,
        logs: MutableList<String> = mutableListOf(),
    ): Boolean = runBlocking {
        WidgetPoller.pollOnceWith(
            deps = deps,
            state = state,
            hasCredentials = hasCredentials,
            nowMs = { nowMs },
            logInfo = { logs += it },
        )
    }

    private fun envelope(id: String = "w1") = WidgetEnvelope(
        id = id,
        type = "text",
        title = "Hello",
        updatedAt = "2026-01-01T00:00:00Z",
        payload = mapOf("body" to "hi"),
    )

    @Test
    fun `single 401 keeps credentials and cache`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(401))
        val state = InMemoryPollStateStore()
        val logs = mutableListOf<String>()

        val wiped = runPoll(deps, state, logs = logs)

        assertFalse(wiped)
        assertEquals(0, deps.clearCredentialsCalls)
        assertEquals(0, deps.clearCacheCalls)
        assertEquals(0, deps.pushCalls)
        assertEquals(1, state.consecutiveAuthFailures)
        val status = state.lastStatus!!
        assertEquals(PollResult.AUTH_FAILED, status.result)
        assertEquals(401, status.httpCode)
        assertEquals(1, status.consecutiveAuthFailures)
        assertTrue(logs.any { it.contains("401") && it.contains("auth-failed-attempt-1-of-2") })
    }

    @Test
    fun `two consecutive auth failures wipe and stay fail-closed`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(401))
        val state = InMemoryPollStateStore()

        assertFalse(runPoll(deps, state))
        assertTrue(runPoll(deps, state))

        assertEquals(1, deps.clearCredentialsCalls)
        assertEquals(1, deps.clearCacheCalls)
        assertEquals(1, deps.pushCalls)
        // Streak resets on wipe so a later re-pair starts clean.
        assertEquals(0, state.consecutiveAuthFailures)
    }

    @Test
    fun `mixed 401 then 403 accumulates one streak`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(401))
        val state = InMemoryPollStateStore()

        assertFalse(runPoll(deps, state))
        deps.outcome = WidgetPoller.PollFetchOutcome.HttpError(403)

        assertTrue(runPoll(deps, state))
        assertEquals(1, deps.clearCredentialsCalls)
        assertEquals(1, deps.clearCacheCalls)
        assertEquals(1, deps.pushCalls)
    }

    @Test
    fun `success after one auth failure resets without wiping`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(401))
        val state = InMemoryPollStateStore()
        assertFalse(runPoll(deps, state))
        assertEquals(1, state.consecutiveAuthFailures)

        deps.outcome = WidgetPoller.PollFetchOutcome.Success(listOf(envelope()))
        val logs = mutableListOf<String>()
        assertFalse(runPoll(deps, state, logs = logs))

        assertEquals(0, deps.clearCredentialsCalls)
        assertEquals(0, deps.clearCacheCalls)
        assertEquals(0, deps.pushCalls)
        assertEquals(1, deps.savedBatches.size)
        assertEquals("w1", deps.savedBatches.single().single().id)
        assertEquals(0, state.consecutiveAuthFailures)
        assertEquals(PollResult.OK, state.lastStatus!!.result)
        assertEquals(200, state.lastStatus!!.httpCode)
        assertTrue(logs.any { it.contains("200") && it.contains("ok") })
    }

    @Test
    fun `non-auth http errors never wipe and never increment`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(500))
        val state = InMemoryPollStateStore()
        val logs = mutableListOf<String>()

        assertFalse(runPoll(deps, state, logs = logs))
        assertFalse(runPoll(deps, state, logs = logs))

        assertEquals(0, deps.clearCredentialsCalls)
        assertEquals(0, deps.clearCacheCalls)
        assertEquals(0, deps.pushCalls)
        assertEquals(0, state.consecutiveAuthFailures)
        val status = state.lastStatus!!
        assertEquals(PollResult.NON_AUTH_ERROR, status.result)
        assertEquals(500, status.httpCode)
        assertTrue(logs.any { it.contains("500") && it.contains("network-error") })
    }

    @Test
    fun `transport failure never wipes and leaves streak untouched`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.TransportFailure)
        val state = InMemoryPollStateStore(consecutiveAuthFailures = 1)

        assertFalse(runPoll(deps, state))

        assertEquals(0, deps.clearCredentialsCalls)
        assertEquals(0, deps.clearCacheCalls)
        assertEquals(1, state.consecutiveAuthFailures)
        val status = state.lastStatus!!
        assertEquals(PollResult.NON_AUTH_ERROR, status.result)
        assertEquals(null, status.httpCode)
    }

    @Test
    fun `missing credentials short-circuit without touching the streak`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(401))
        val state = InMemoryPollStateStore(consecutiveAuthFailures = 1)

        assertFalse(runPoll(deps, state, hasCredentials = false))

        assertEquals(0, deps.fetchCalls)
        assertEquals(0, deps.clearCredentialsCalls)
        assertEquals(1, state.consecutiveAuthFailures)
        assertEquals(PollResult.NO_CREDENTIALS, state.lastStatus!!.result)
    }

    @Test
    fun `poll status carries code and timestamp`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(403))
        val state = InMemoryPollStateStore()
        val now = 1_700_000_000_000L

        runPoll(deps, state, nowMs = now)

        val status = state.lastStatus!!
        assertEquals(403, status.httpCode)
        assertEquals(now, status.timestampMs)
        assertTrue(status.displayText().contains("auth-failed-attempt-1-of-2"))
        assertTrue(status.displayText().contains("403"))
    }

    @Test
    fun `success logs parsed and upserted counts`() {
        val deps = FakeDeps(
            WidgetPoller.PollFetchOutcome.Success(listOf(envelope("w1"), envelope("w2"))),
        )
        val state = InMemoryPollStateStore()
        val logs = mutableListOf<String>()

        assertFalse(runPoll(deps, state, logs = logs))

        assertEquals(1, deps.savedBatches.size)
        assertEquals(2, deps.savedBatches.single().size)
        assertTrue(logs.any { it.contains("200") && it.contains("ok") })
        assertTrue(logs.any { it.contains("parsed=2") && it.contains("upserted=2") })
    }

    @Test
    fun `empty success never reaches the DAO layer and logs zero counts`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.Success(emptyList()))
        val state = InMemoryPollStateStore()
        val logs = mutableListOf<String>()

        assertFalse(runPoll(deps, state, logs = logs))

        // The DAO upsert is skipped: an empty server list keeps the last
        // cache instead of risking a wipe.
        assertTrue(deps.savedBatches.isEmpty())
        assertEquals(PollResult.OK, state.lastStatus!!.result)
        assertTrue(logs.any { it.contains("parsed=0") && it.contains("upserted=0") })
    }

    @Test
    fun `cache write failure keeps the cache and leaves the streak untouched`() {
        val deps = FakeDeps(
            WidgetPoller.PollFetchOutcome.Success(listOf(envelope())),
            failSave = true,
        )
        val state = InMemoryPollStateStore()
        val logs = mutableListOf<String>()

        assertFalse(runPoll(deps, state, logs = logs))

        assertEquals(PollResult.NON_AUTH_ERROR, state.lastStatus!!.result)
        assertEquals(0, state.consecutiveAuthFailures)
        assertTrue(logs.any { it.contains("network-error") })
    }

    @Test
    fun `poll logs never contain tokens or bodies`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(401))
        val state = InMemoryPollStateStore()
        val logs = mutableListOf<String>()
        runPoll(deps, state, logs = logs)
        deps.outcome = WidgetPoller.PollFetchOutcome.Success(listOf(envelope()))
        runPoll(deps, state, logs = logs)

        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            assertFalse("log leaks secret: $line", line.contains("Bearer"))
            assertFalse("log leaks secret: $line", line.contains("device_token"))
        }
    }
}
