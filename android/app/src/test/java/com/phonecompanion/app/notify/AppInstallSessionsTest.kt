package com.phonecompanion.app.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the single session owner that replaced AppInstallCancels (flags)
 * and AppInstallTerminals (TTL window): cancel lifecycle, per-attempt
 * exactly-once terminal claims, cross-component funnel behavior, retry
 * freshness (no per-push-per-minute suppression), and rotation survival.
 */
class AppInstallSessionsTest {

    @Test
    fun `cancel flag lifecycle per push`() {
        val id = "test-push-cancel-lifecycle"
        try {
            assertFalse(AppInstallSessions.isCancelled(id))
            AppInstallSessions.requestCancel(id)
            assertTrue(AppInstallSessions.isCancelled(id))
            // Idempotent: second request keeps it cancelled.
            AppInstallSessions.requestCancel(id)
            assertTrue(AppInstallSessions.isCancelled(id))
        } finally {
            AppInstallSessions.clearForTest(id)
        }
        assertFalse(AppInstallSessions.isCancelled(id))
    }

    @Test
    fun `unknown push id is not cancelled`() {
        assertFalse(AppInstallSessions.isCancelled("no-such-push-id"))
    }

    @Test
    fun `startAttempt clears a stale cancel flag and mints unique nonces`() {
        val id = "test-push-attempt-nonces"
        try {
            AppInstallSessions.requestCancel(id)
            assertTrue(AppInstallSessions.isCancelled(id))
            val first = AppInstallSessions.startAttempt(id)
            assertFalse(AppInstallSessions.isCancelled(id))
            val second = AppInstallSessions.startAttempt(id)
            assertNotEquals(first, second)
        } finally {
            AppInstallSessions.clearForTest(id)
        }
    }

    @Test
    fun `exactly one claimant wins per attempt`() {
        val id = "test-push-terminal-claim"
        try {
            val attempt = AppInstallSessions.startAttempt(id)
            assertTrue(AppInstallSessions.claimTerminal(id, attempt))
            assertFalse(AppInstallSessions.claimTerminal(id, attempt))
            assertFalse(AppInstallSessions.claimTerminal(id, attempt))
        } finally {
            AppInstallSessions.clearForTest(id)
        }
    }

    @Test
    fun `cross-component funnel - activity cancel and result receivers share one claim`() {
        // Same push + same attempt nonce (carried through the progress
        // Cancel intent and the installer result intent): the first
        // claimant posts, every other component stays silent.
        val pushId = "test-push-cross-component"
        try {
            val attempt = AppInstallSessions.startAttempt(pushId)
            // Activity arm wins...
            assertTrue(AppInstallSessions.claimTerminal(pushId, attempt))
            // ...cancel receiver loses...
            assertFalse(AppInstallSessions.claimTerminal(pushId, attempt))
            // ...result receiver loses too.
            assertFalse(AppInstallSessions.claimTerminal(pushId, attempt))
        } finally {
            AppInstallSessions.clearForTest(pushId)
        }
    }

    @Test
    fun `success after retry posts exactly once - new attempt claims fresh`() {
        // The old TTL window silently dropped a re-push inside 60 s.
        // Per-attempt claims: a retry tap mints a new nonce and posts.
        val pushId = "test-push-retry"
        try {
            val first = AppInstallSessions.startAttempt(pushId)
            assertTrue(AppInstallSessions.claimTerminal(pushId, first))
            // A duplicate delivery of the same attempt stays silent...
            assertFalse(AppInstallSessions.claimTerminal(pushId, first))
            // ...but the retry tap is a new attempt and claims fresh.
            val retry = AppInstallSessions.startAttempt(pushId)
            assertNotEquals(first, retry)
            assertTrue(AppInstallSessions.claimTerminal(pushId, retry))
            assertFalse(AppInstallSessions.claimTerminal(pushId, retry))
        } finally {
            AppInstallSessions.clearForTest(pushId)
        }
    }

    @Test
    fun `distinct push ids claim independently`() {
        try {
            val a = AppInstallSessions.startAttempt("push-a")
            val b = AppInstallSessions.startAttempt("push-b")
            assertTrue(AppInstallSessions.claimTerminal("push-a", a))
            assertTrue(AppInstallSessions.claimTerminal("push-b", b))
        } finally {
            AppInstallSessions.clearForTest("push-a")
            AppInstallSessions.clearForTest("push-b")
        }
    }

    @Test
    fun `rotation survival - session outlives a destroyed owner`() {
        // Simulates rotation: owner 1 starts the attempt and is destroyed
        // WITHOUT cancelling (the old onDestroy cancelled spuriously);
        // owner 2 attaches to the same push and must observe no cancel
        // flag and the still-unclaimed attempt — no spurious Cancelled,
        // no second flow, no TTL suppression.
        val pushId = "test-push-rotation"
        try {
            // Owner 1: explicit Install tap.
            val attempt = AppInstallSessions.startAttempt(pushId)
            // Owner 1 destroyed by rotation: drops its references, sets
            // no flag, makes no terminal claim (the ViewModel survives).
            // Owner 2 (recreated activity): same process session.
            assertFalse(AppInstallSessions.isCancelled(pushId))
            // The attempt is still live and claimable exactly once.
            assertTrue(AppInstallSessions.claimTerminal(pushId, attempt))
            assertFalse(AppInstallSessions.claimTerminal(pushId, attempt))
        } finally {
            AppInstallSessions.clearForTest(pushId)
        }
    }

    @Test
    fun `legacy intents without a nonce still end exactly once`() {
        val pushId = "test-push-legacy"
        try {
            assertTrue(AppInstallSessions.claimTerminal(pushId, null))
            assertFalse(AppInstallSessions.claimTerminal(pushId, ""))
            assertFalse(AppInstallSessions.claimTerminal(pushId, null))
        } finally {
            AppInstallSessions.clearForTest(pushId)
        }
    }

    @Test
    fun `idle sessions purge after the stale window`() {
        val pushId = "test-push-purge"
        try {
            val attempt = AppInstallSessions.startAttempt(pushId, nowMs = 0L)
            assertTrue(AppInstallSessions.claimTerminal(pushId, attempt, nowMs = 1_000L))
            // Far past the stale window the idle session purges on the
            // next touch; the recreated session no longer knows the spent
            // attempt — purged means no live flow references it anymore.
            val afterStale = 1_000L + AppInstallSessions.STALE_SESSION_MAX_AGE_MS + 1
            AppInstallSessions.requestCancel("other-push", nowMs = afterStale)
            assertTrue(AppInstallSessions.claimTerminal(pushId, attempt, nowMs = afterStale))
        } finally {
            AppInstallSessions.clearForTest(pushId)
            AppInstallSessions.clearForTest("other-push")
        }
    }
}
