package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInstallResultTest {

    @Test
    fun `success maps to Installed`() {
        assertEquals(
            AppInstallResult.Outcome.Installed,
            AppInstallResult.classify(AppInstallResult.STATUS_SUCCESS, null),
        )
    }

    @Test
    fun `user abort maps to UserCancelled with no retry`() {
        assertEquals(
            AppInstallResult.Outcome.UserCancelled,
            AppInstallResult.classify(AppInstallResult.STATUS_FAILURE_ABORTED, "abort"),
        )
    }

    @Test
    fun `failures map to Failed carrying the installer message`() {
        for (status in listOf(
            AppInstallResult.STATUS_FAILURE,
            AppInstallResult.STATUS_FAILURE_BLOCKED,
            AppInstallResult.STATUS_FAILURE_CONFLICT,
            AppInstallResult.STATUS_FAILURE_INCOMPATIBLE,
            AppInstallResult.STATUS_FAILURE_INVALID,
            AppInstallResult.STATUS_FAILURE_STORAGE,
        )) {
            val outcome = AppInstallResult.classify(status, "msg-$status")
            assertTrue(outcome is AppInstallResult.Outcome.Failed)
            assertEquals("msg-$status", (outcome as AppInstallResult.Outcome.Failed).statusMessage)
        }
    }

    @Test
    fun `unknown and pending codes fail closed`() {
        assertTrue(
            AppInstallResult.classify(AppInstallResult.STATUS_PENDING, null)
                is AppInstallResult.Outcome.Failed,
        )
        assertTrue(
            AppInstallResult.classify(999, "weird") is AppInstallResult.Outcome.Failed,
        )
    }
}

class AppInstallCancelsTest {

    @Test
    fun `cancel flag lifecycle`() {
        val id = "test-push-cancel-lifecycle"
        try {
            assertFalse(AppInstallCancels.isCancelled(id))
            AppInstallCancels.requestCancel(id)
            assertTrue(AppInstallCancels.isCancelled(id))
            // Idempotent: second request keeps it cancelled.
            AppInstallCancels.requestCancel(id)
            assertTrue(AppInstallCancels.isCancelled(id))
        } finally {
            AppInstallCancels.clear(id)
        }
        assertFalse(AppInstallCancels.isCancelled(id))
    }

    @Test
    fun `unknown push id is not cancelled`() {
        assertFalse(AppInstallCancels.isCancelled("no-such-push-id"))
    }
}

class AppInstallTerminalsTest {

    @Test
    fun `exactly one claimant wins`() {
        val id = "test-push-terminal-claim"
        try {
            assertTrue(AppInstallTerminals.claimTerminal(id, nowMs = 1_000L))
            assertFalse(AppInstallTerminals.claimTerminal(id, nowMs = 1_000L))
            assertFalse(AppInstallTerminals.claimTerminal(id, nowMs = 1_001L))
        } finally {
            AppInstallTerminals.clearForTest(id)
        }
        // After reset, the same id can claim again.
        try {
            assertTrue(AppInstallTerminals.claimTerminal(id, nowMs = 2_000L))
        } finally {
            AppInstallTerminals.clearForTest(id)
        }
    }

    @Test
    fun `distinct push ids claim independently`() {
        try {
            assertTrue(AppInstallTerminals.claimTerminal("push-a", nowMs = 1_000L))
            assertTrue(AppInstallTerminals.claimTerminal("push-b", nowMs = 1_000L))
        } finally {
            AppInstallTerminals.clearForTest("push-a")
            AppInstallTerminals.clearForTest("push-b")
        }
    }

    @Test
    fun `expired claim is reclaimable without reset`() {
        val id = "test-push-terminal-expiry"
        try {
            assertTrue(AppInstallTerminals.claimTerminal(id, nowMs = 0L))
            assertFalse(AppInstallTerminals.claimTerminal(id, nowMs = 1_000L))
            // Past the TTL the same id is a new flow and may claim again.
            assertTrue(
                AppInstallTerminals.claimTerminal(
                    id,
                    nowMs = AppInstallTerminals.CLAIM_TTL_MS + 1,
                ),
            )
        } finally {
            AppInstallTerminals.clearForTest(id)
        }
    }
}
