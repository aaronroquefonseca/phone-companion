package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-command download lock: single-writer acquire/refuse,
 * release, orphaned-lock recovery after process death, the stale-window
 * boundary, and the push-id registry the stale purge consults.
 */
class AppInstallDownloadLockTest {

    /** Memory-backed store honoring the stale window (mirrors the prefs store). */
    private class FakeStore(
        private val nowMs: () -> Long = { System.currentTimeMillis() },
    ) : AppInstallDownloadLocks.LockStore {
        val disk = mutableMapOf<String, Long>()

        override fun lockedAtMs(commandId: String): Long? {
            val at = disk[commandId] ?: return null
            return if (AppInstallDownloadLocks.isStaleLock(at, nowMs())) null else at
        }

        override fun markLocked(commandId: String, nowMs: Long) {
            disk[commandId] = nowMs
        }

        override fun clearLocked(commandId: String) {
            disk.remove(commandId)
        }
    }

    @Test
    fun `first acquire wins and a concurrent starter is refused`() {
        val store = FakeStore()
        try {
            assertEquals(
                AppInstallDownloadLocks.Acquire.ACQUIRED,
                AppInstallDownloadLocks.acquire("cmd", "push", store),
            )
            // Same command while held: refused — never a second writer.
            assertEquals(
                AppInstallDownloadLocks.Acquire.IN_FLIGHT,
                AppInstallDownloadLocks.acquire("cmd", "push", store),
            )
            assertTrue(AppInstallDownloadLocks.isInFlight("cmd"))
        } finally {
            AppInstallDownloadLocks.release("cmd", store)
        }
        assertFalse(AppInstallDownloadLocks.isInFlight("cmd"))
    }

    @Test
    fun `release frees the lock for a fresh acquire`() {
        val store = FakeStore()
        AppInstallDownloadLocks.acquire("cmd", "push", store)
        AppInstallDownloadLocks.release("cmd", store)
        try {
            assertEquals(
                AppInstallDownloadLocks.Acquire.ACQUIRED,
                AppInstallDownloadLocks.acquire("cmd", "push", store),
            )
        } finally {
            AppInstallDownloadLocks.release("cmd", store)
        }
    }

    @Test
    fun `orphaned persisted lock recovers on the next poll`() {
        val store = FakeStore()
        try {
            AppInstallDownloadLocks.acquire("cmd", "push", store)
            // Process death mid-download: the in-memory holder is gone, only
            // the persisted mirror survives. The next poll recovers rather
            // than wedging (the downloader overwrites the partial).
            AppInstallDownloadLocks.clearForTest("cmd")
            assertEquals(
                AppInstallDownloadLocks.Acquire.RECOVERED,
                AppInstallDownloadLocks.acquire("cmd", "push", store),
            )
        } finally {
            AppInstallDownloadLocks.release("cmd", store)
        }
    }

    @Test
    fun `stale persisted lock reads absent and acquires fresh`() {
        val store = FakeStore()
        store.markLocked(
            "cmd",
            System.currentTimeMillis() - AppInstallDownloadLocks.STALE_LOCK_MAX_AGE_MS - 1_000,
        )
        try {
            assertEquals(
                AppInstallDownloadLocks.Acquire.ACQUIRED,
                AppInstallDownloadLocks.acquire("cmd", "push", store),
            )
        } finally {
            AppInstallDownloadLocks.release("cmd", store)
        }
    }

    @Test
    fun `stale boundary is a named constant`() {
        val at = 1_000_000L
        assertFalse(
            AppInstallDownloadLocks.isStaleLock(at, at + AppInstallDownloadLocks.STALE_LOCK_MAX_AGE_MS - 1),
        )
        assertTrue(
            AppInstallDownloadLocks.isStaleLock(at, at + AppInstallDownloadLocks.STALE_LOCK_MAX_AGE_MS),
        )
    }

    @Test
    fun `inFlightPushIds tracks holders for the stale purge`() {
        val store = FakeStore()
        try {
            AppInstallDownloadLocks.acquire("cmd-a", "push-a", store)
            AppInstallDownloadLocks.acquire("cmd-b", "push-b", store)
            assertEquals(setOf("push-a", "push-b"), AppInstallDownloadLocks.inFlightPushIds())
            AppInstallDownloadLocks.release("cmd-a", store)
            assertEquals(setOf("push-b"), AppInstallDownloadLocks.inFlightPushIds())
        } finally {
            AppInstallDownloadLocks.release("cmd-a", store)
            AppInstallDownloadLocks.release("cmd-b", store)
        }
        assertTrue(AppInstallDownloadLocks.inFlightPushIds().isEmpty())
    }

    @Test
    fun `distinct command ids acquire independently`() {
        val store = FakeStore()
        try {
            assertEquals(AppInstallDownloadLocks.Acquire.ACQUIRED, AppInstallDownloadLocks.acquire("a", "pa", store))
            assertEquals(AppInstallDownloadLocks.Acquire.ACQUIRED, AppInstallDownloadLocks.acquire("b", "pb", store))
        } finally {
            AppInstallDownloadLocks.release("a", store)
            AppInstallDownloadLocks.release("b", store)
        }
    }

    @Test
    fun `release is idempotent and clears the persisted mirror`() {
        val store = FakeStore()
        AppInstallDownloadLocks.acquire("cmd", "push", store)
        AppInstallDownloadLocks.release("cmd", store)
        AppInstallDownloadLocks.release("cmd", store)
        assertNull(store.disk["cmd"])
        assertFalse(AppInstallDownloadLocks.isInFlight("cmd"))
    }
}
