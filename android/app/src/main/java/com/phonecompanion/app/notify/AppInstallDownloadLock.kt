package com.phonecompanion.app.notify

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-command download lock for the remote APK install flow.
 *
 * Two concurrent poll paths (the scheduled/widget poll and the FCM wake
 * worker, or a manual refresh racing a wake) can both fetch the same fresh
 * outbox command before either marks it posted, so both would otherwise
 * start a download to the SAME cache file and corrupt it (interleaved
 * writes → hash mismatch → silent failure). This lock makes the download
 * single-writer per command id: the first starter acquires and every later
 * starter is refused with a logged line — never a second writer to the
 * same file.
 *
 * The in-memory map is the live truth (single-process app). The persisted
 * store is a crash-recovery mirror: when the process dies mid-download the
 * in-memory holder is gone but the persisted entry remains, so the next
 * poll's [acquire] recovers it ([Acquire.RECOVERED] → re-download; the
 * downloader overwrites the partial) instead of wedging future installs.
 * A persisted entry older than [STALE_LOCK_MAX_AGE_MS] reads as absent
 * (stale), bounding the store's own lifetime.
 *
 * The lock also tracks the push id so the stale-APK purge can skip the
 * files a live flow still references ([inFlightPushIds]).
 *
 * Pure JVM logic (injectable clock + store), unit-testable without an
 * emulator; the Android store is [PrefsAppInstallLockStore].
 */
object AppInstallDownloadLocks {

    /** A persisted lock older than this reads as absent (stale). */
    const val STALE_LOCK_MAX_AGE_MS = 30L * 60L * 1000L // 30 min

    /** Acquire disposition for one command id. */
    enum class Acquire {
        /** Free: we own the lock — start the download. */
        ACQUIRED,

        /** Orphaned persisted lock recovered: re-download (no wedge). */
        RECOVERED,

        /** Another starter holds it: refuse (never a second writer). */
        IN_FLIGHT,
    }

    private data class Entry(val pushId: String, val lockedAtMs: Long)

    private val inFlight = ConcurrentHashMap<String, Entry>()

    /** Persisted lock store (crash-recovery mirror). Never throws. */
    interface LockStore {
        /** Lock timestamp for [commandId], or null when absent/stale. */
        fun lockedAtMs(commandId: String): Long?

        fun markLocked(commandId: String, nowMs: Long)

        fun clearLocked(commandId: String)
    }

    /**
     * True when a lock at [lockedAtMs] is past the retention window at
     * [nowMs] (stale — reads as absent). Pure so the boundary is
     * unit-testable.
     */
    fun isStaleLock(lockedAtMs: Long, nowMs: Long): Boolean =
        nowMs - lockedAtMs >= STALE_LOCK_MAX_AGE_MS

    /**
     * Acquires the download lock for one command. Returns [Acquire.ACQUIRED]
     * (free) or [Acquire.RECOVERED] (an orphaned persisted lock from a dead
     * process was cleared) — both mean "proceed to download" — or
     * [Acquire.IN_FLIGHT] when another starter already holds it (refuse).
     * Never throws.
     */
    fun acquire(
        commandId: String,
        pushId: String,
        store: LockStore,
        nowMs: Long = System.currentTimeMillis(),
    ): Acquire {
        if (inFlight.containsKey(commandId)) return Acquire.IN_FLIGHT
        if (inFlight.putIfAbsent(commandId, Entry(pushId, nowMs)) != null) {
            return Acquire.IN_FLIGHT
        }
        val persisted = store.lockedAtMs(commandId)
        store.markLocked(commandId, nowMs)
        return if (persisted != null) Acquire.RECOVERED else Acquire.ACQUIRED
    }

    /** Releases the lock (in-memory first, then persisted). Never throws. */
    fun release(commandId: String, store: LockStore) {
        inFlight.remove(commandId)
        store.clearLocked(commandId)
    }

    /** Push ids currently protected by a live lock (for the stale purge). */
    fun inFlightPushIds(): Set<String> =
        inFlight.values.mapTo(HashSet(inFlight.size)) { it.pushId }

    fun isInFlight(commandId: String): Boolean = inFlight.containsKey(commandId)

    /** Test-only reset. Production paths never clear (see class kdoc). */
    fun clearForTest(commandId: String) {
        inFlight.remove(commandId)
    }
}

/**
 * Plain-prefs [AppInstallDownloadLocks.LockStore] (production). Command ids
 * are opaque server-issued ids (not secret), so plain prefs — never
 * encrypted storage — matches [AppInstallPostedStore]. Entries past the
 * stale window read as absent. Never throws.
 */
class PrefsAppInstallLockStore(context: Context) : AppInstallDownloadLocks.LockStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun lockedAtMs(commandId: String): Long? {
        if (commandId.isBlank()) return null
        return try {
            val at = prefs.getLong(KEY_PREFIX + commandId, -1L)
            if (at < 0) null else {
                // A lock past the retention window is stale: absent, so the
                // next poll re-downloads rather than wedging forever.
                if (AppInstallDownloadLocks.isStaleLock(at, System.currentTimeMillis())) null else at
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun markLocked(commandId: String, nowMs: Long) {
        if (commandId.isBlank()) return
        try {
            prefs.edit().putLong(KEY_PREFIX + commandId, nowMs).apply()
        } catch (_: Exception) {
            // Best-effort: losing the mirror only costs a re-download after
            // a process death mid-download, never correctness.
        }
    }

    override fun clearLocked(commandId: String) {
        if (commandId.isBlank()) return
        try {
            prefs.edit().remove(KEY_PREFIX + commandId).apply()
        } catch (_: Exception) {
            // Best-effort: an orphaned entry is recovered on the next poll.
        }
    }

    companion object {
        internal const val PREFS_NAME = "pc_app_install_locks"
        internal const val KEY_PREFIX = "lock_"
    }
}
