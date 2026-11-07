package com.phonecompanion.app.notify

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exactly-once guard for terminal user-visible states, keyed by push id.
 *
 * Pure JVM (zero Android imports): unit-testable. Two paths can race to
 * post the terminal notification for one push — e.g. the download-cancel
 * broadcast receiver and the activity observing the same cancel flag. The
 * winner ([claimTerminal] returning true) posts; the loser stays silent.
 *
 * Claims expire after [CLAIM_TTL_MS] (time-based, no explicit release):
 * an explicit `clear` after posting would reopen the race — a loser
 * observing the cancel just after the winner's clear would claim again
 * and double-post. A re-push of the same id after the TTL is treated as
 * a new flow and may claim again; a duplicate redelivery inside the TTL
 * stays silent, which matches the stable-notifyId replace behavior.
 */
object AppInstallTerminals {

    const val CLAIM_TTL_MS = 60_000L

    private data class Claim(val locked: AtomicBoolean, @Volatile var atMs: Long)

    private val claims = ConcurrentHashMap<String, Claim>()

    /**
     * Claims the terminal post for [pushId]. Returns true for exactly one
     * caller per TTL window; every other caller must stay silent.
     * [nowMs] is injectable for unit tests (defaults to wall clock).
     */
    fun claimTerminal(pushId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        purgeExpired(nowMs)
        val claim = claims.getOrPut(pushId) { Claim(AtomicBoolean(false), 0L) }
        synchronized(claim) {
            if (claim.locked.get() && nowMs - claim.atMs < CLAIM_TTL_MS) return false
            claim.locked.set(true)
            claim.atMs = nowMs
            return true
        }
    }

    /** Test-only reset. Production paths never clear (see class kdoc). */
    fun clearForTest(pushId: String) {
        claims.remove(pushId)
    }

    private fun purgeExpired(nowMs: Long) {
        // Only locked-and-expired entries are purged: an unlocked entry is
        // always mid-claim by its creator (locked immediately after under
        // the claim monitor), and dropping it would fork the claim.
        claims.entries.removeIf { (_, claim) ->
            claim.locked.get() && nowMs - claim.atMs >= CLAIM_TTL_MS
        }
    }
}
