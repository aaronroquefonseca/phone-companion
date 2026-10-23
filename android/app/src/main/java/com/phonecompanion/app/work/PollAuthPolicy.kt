package com.phonecompanion.app.work

/**
 * Consecutive-auth-failure policy for the widget poll.
 *
 * A single HTTP 401/403 must NOT wipe credentials: one mismatched poll
 * (stale token, transient server-side mismatch) wiped a physical tablet —
 * credentials forgotten, widget cache dropped, all widgets + previews
 * permanently empty. Instead this policy tracks *consecutive* auth
 * failures and wipes only after [MAX_CONSECUTIVE_AUTH_FAILURES] in a row
 * (N=2: covers one manual + one background poll, or two polls of either
 * kind).
 *
 * - Any successful poll resets the streak to 0.
 * - Non-auth failures (network errors, HTTP 5xx/4xx other than 401/403)
 *   NEVER increment the streak and keep the last cache.
 * - A genuine revocation still converges fast: the second consecutive
 *   401/403 wipes (clearCredentials + clearAll + fail-closed states) and
 *   stays fail-closed until re-pair. The streak resets to 0 on wipe so a
 *   later re-pair starts clean.
 *
 * The streak lives in a persisted *plain* (non-encrypted) preference —
 * see [PollStateStore]: it must survive credential state (it is read to
 * decide whether to clear credentials) and it is not secret.
 */
object PollAuthPolicy {

    /** Consecutive 401/403 polls required before [WidgetPoller] wipes. */
    const val MAX_CONSECUTIVE_AUTH_FAILURES = 2

    /** Auth-mismatch codes per the api-contract (401/403). */
    fun isAuthFailureCode(code: Int): Boolean = code == 401 || code == 403

    /**
     * Next streak value. Exactly one of the outcomes applies per poll:
     * success resets, an auth failure increments, anything else (network
     * error, non-auth HTTP) leaves the streak untouched.
     */
    fun nextConsecutiveAuthFailures(
        current: Int,
        success: Boolean,
        authFailure: Boolean,
    ): Int = when {
        success -> 0
        authFailure -> current + 1
        else -> current
    }

    /** True once the streak has reached the wipe threshold. */
    fun shouldWipe(consecutiveAuthFailures: Int): Boolean =
        consecutiveAuthFailures >= MAX_CONSECUTIVE_AUTH_FAILURES
}
