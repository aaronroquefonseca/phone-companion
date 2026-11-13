package com.phonecompanion.app.notify

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Push-scoped session owner for the remote APK install flow — the single
 * funnel for cancellation and exactly-once terminal posts.
 *
 * Replaces the two process-wide maps it subsumes ([AppInstallCancels]
 * flags and the TTL claim window): one session per push id holds the
 * cancel flag plus the set of claimed attempt nonces. Every terminal post
 * in the flow (activity arms, download-cancel receiver, installer-result
 * receiver) goes through [claimTerminal].
 *
 * Exactly-once is scoped per download attempt, never per push id per
 * minute: each download start (auto on receipt, or an explicit Install
 * tap) mints a fresh nonce via
 * [startAttempt], carried through the progress Cancel intent and the
 * installer result intent into every claim. A re-push (or a retry tap)
 * mints a new attempt and may post again; a duplicate delivery of the
 * same attempt stays silent.
 *
 * Pure JVM (zero Android imports): the cancel path, the per-attempt claim
 * funnel, and the rotation-survival shape (process-scoped state shared
 * across destroyed/recreated owners) are unit-testable without an
 * emulator.
 */
object AppInstallSessions {

    /**
     * Idle sessions are purged after this long without activity. Memory
     * hygiene only — never a suppression window: claims are per attempt,
     * so purging an idle session cannot silence a live flow (the flow
     * re-creates its session on next touch).
     */
    const val STALE_SESSION_MAX_AGE_MS = 24L * 60L * 60L * 1000L // 24 h

    /** Fallback claim key when an intent carries no attempt nonce (legacy). */
    internal const val LEGACY_ATTEMPT_KEY = "legacy"

    private class Session(@Volatile var lastActiveMs: Long) {
        val cancelled = AtomicBoolean(false)
        val claimed = ConcurrentHashMap<String, Boolean>()
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * Starts a new download attempt for [pushId]: drops any stale
     * cancel flag from an earlier dead flow and returns a fresh nonce
     * the caller threads into every terminal claim for this attempt.
     */
    fun startAttempt(pushId: String, nowMs: Long = System.currentTimeMillis()): String {
        purgeExpired(nowMs)
        val session = sessions.getOrPut(pushId) { Session(nowMs) }
        session.lastActiveMs = nowMs
        session.cancelled.set(false)
        return UUID.randomUUID().toString()
    }

    /** Returns true when cancellation was requested for [pushId]. */
    fun isCancelled(pushId: String): Boolean = sessions[pushId]?.cancelled?.get() == true

    /** Requests cancellation for [pushId] (idempotent). */
    fun requestCancel(pushId: String, nowMs: Long = System.currentTimeMillis()) {
        purgeExpired(nowMs)
        val session = sessions.getOrPut(pushId) { Session(nowMs) }
        session.lastActiveMs = nowMs
        session.cancelled.set(true)
    }

    /**
     * Claims the terminal post for ([pushId], [attempt]). Returns true for
     * exactly one caller per attempt; every other claimant of the same
     * attempt (activity arm vs cancel receiver vs destroy path) stays
     * silent. A null/blank [attempt] (pre-nonce intents) falls back to a
     * single legacy key — still exactly-once per push. [nowMs] is
     * injectable for unit tests (defaults to wall clock).
     */
    fun claimTerminal(
        pushId: String,
        attempt: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        purgeExpired(nowMs)
        val session = sessions.getOrPut(pushId) { Session(nowMs) }
        session.lastActiveMs = nowMs
        val key = if (attempt.isNullOrBlank()) LEGACY_ATTEMPT_KEY else attempt
        return session.claimed.putIfAbsent(key, true) == null
    }

    /**
     * Read-only check: has the terminal post for ([pushId], [attempt])
     * already been claimed? Never claims — the result-receiver watchdog
     * ([AppInstallResultWatchdogWorker]) uses it to stay silent when the
     * installer result arrived in time, without stealing the claim from a
     * terminal that has not posted yet. Same null/blank [attempt] legacy
     * key as [claimTerminal].
     */
    fun isTerminalClaimed(pushId: String, attempt: String?): Boolean {
        val session = sessions[pushId] ?: return false
        val key = if (attempt.isNullOrBlank()) LEGACY_ATTEMPT_KEY else attempt
        return session.claimed.containsKey(key)
    }

    /** Test-only reset. Production paths never clear (see class kdoc). */
    fun clearForTest(pushId: String) {
        sessions.remove(pushId)
    }

    private fun purgeExpired(nowMs: Long) {
        sessions.entries.removeIf { (_, session) ->
            nowMs - session.lastActiveMs >= STALE_SESSION_MAX_AGE_MS
        }
    }
}
