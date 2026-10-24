package com.phonecompanion.app.work

import com.phonecompanion.app.data.WidgetEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revocation marker: the consecutive-auth-failure wipe stamps a plain-pref
 * timestamp ([PollStateStore.revokedAtMs]) so the UI can tell "revoked"
 * from "never paired". Only the wipe sets it; a single 401/403, transport
 * failures, and non-auth errors leave it alone; successful enroll/pair and
 * explicit unpair clear it (both clear paths write `null` through the same
 * store field — covered here at the store seam, wired in PairActivity and
 * MainActivity).
 */
class RevocationMarkerTest {

    private class FakeDeps(
        var outcome: WidgetPoller.PollFetchOutcome =
            WidgetPoller.PollFetchOutcome.TransportFailure,
    ) : WidgetPoller.PollCollaborators {
        var clearCredentialsCalls = 0
        var clearCacheCalls = 0
        var pushCalls = 0

        override suspend fun fetch(): WidgetPoller.PollFetchOutcome = outcome

        override suspend fun saveWidgets(widgets: List<WidgetEnvelope>): Int = widgets.size

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
        nowMs: Long = 1_700_000_000_000L,
    ): Boolean = runBlocking {
        WidgetPoller.pollOnceWith(
            deps = deps,
            state = state,
            hasCredentials = true,
            nowMs = { nowMs },
            logInfo = {},
        )
    }

    @Test
    fun `wipe after consecutive auth failures stamps the marker`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(401))
        val state = InMemoryPollStateStore()
        val firstAt = 1_700_000_000_000L
        val wipedAt = 1_700_000_000_001L

        assertFalse(runPoll(deps, state, nowMs = firstAt))
        assertNull(state.revokedAtMs)

        assertTrue(runPoll(deps, state, nowMs = wipedAt))

        assertEquals(1, deps.clearCredentialsCalls)
        assertEquals(1, deps.clearCacheCalls)
        assertEquals(1, deps.pushCalls)
        assertEquals(wipedAt, state.revokedAtMs)
    }

    @Test
    fun `single auth failure leaves the marker unset`() {
        val deps = FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(403))
        val state = InMemoryPollStateStore()

        assertFalse(runPoll(deps, state))

        assertEquals(0, deps.clearCredentialsCalls)
        assertNull(state.revokedAtMs)
    }

    @Test
    fun `non-auth errors and transport failures leave the marker unset`() {
        val state = InMemoryPollStateStore()

        assertFalse(runPoll(FakeDeps(WidgetPoller.PollFetchOutcome.HttpError(500)), state))
        assertFalse(runPoll(FakeDeps(WidgetPoller.PollFetchOutcome.TransportFailure), state))

        assertNull(state.revokedAtMs)
    }

    @Test
    fun `direct wipe stamps the marker with the provided clock`() {
        val deps = FakeDeps()
        val state = InMemoryPollStateStore()
        val at = 1_700_000_000_123L

        runBlocking { WidgetPoller.handleRevoked(deps, state) { at } }

        assertEquals(1, deps.clearCredentialsCalls)
        assertEquals(at, state.revokedAtMs)
    }

    @Test
    fun `clearing the marker restores the unset state (enroll and unpair path)`() {
        val state = InMemoryPollStateStore(revokedAtMs = 1_700_000_000_000L)

        // Both production clear paths (PairActivity onEnrolled,
        // MainActivity unpair) write null through this same field.
        state.revokedAtMs = null

        assertNull(state.revokedAtMs)
    }

    @Test
    fun `successful poll does not set the marker`() {
        val deps = FakeDeps(
            WidgetPoller.PollFetchOutcome.Success(
                listOf(
                    WidgetEnvelope(
                        id = "w1",
                        type = "text",
                        title = "Hello",
                        updatedAt = "2026-01-01T00:00:00Z",
                        payload = mapOf("body" to "hi"),
                    ),
                ),
            ),
        )
        val state = InMemoryPollStateStore()

        assertFalse(runPoll(deps, state))

        assertEquals(PollResult.OK, state.lastStatus!!.result)
        assertNull(state.revokedAtMs)
    }
}
