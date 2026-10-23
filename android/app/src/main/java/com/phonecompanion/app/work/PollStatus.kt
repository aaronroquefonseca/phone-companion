package com.phonecompanion.app.work

import java.time.Instant

/** Machine-readable outcome of the last widget poll. */
enum class PollResult {
    OK,
    AUTH_FAILED,
    NON_AUTH_ERROR,
    NO_CREDENTIALS,
}

/**
 * Last widget-poll result, persisted by [WidgetPoller] after every poll
 * and readable by UI ([com.phonecompanion.app.ui.WidgetListUi]).
 *
 * [consecutiveAuthFailures] is the [PollAuthPolicy] streak *after* this
 * poll; [httpCode] is the HTTP status (null for transport failures and
 * missing credentials); [timestampMs] is `System.currentTimeMillis()` at
 * the poll. Nothing here is secret: no tokens, no bodies — safe for plain
 * prefs and for display.
 */
data class PollStatus(
    val result: PollResult,
    val httpCode: Int?,
    val timestampMs: Long,
    val consecutiveAuthFailures: Int,
) {
    /**
     * Human-/test-stable summary. Auth failures render as
     * `auth-failed-attempt-x-of-N`; non-auth errors as `network-error`
     * with the HTTP code when known; every line carries the poll time.
     */
    fun displayText(): String {
        val at = try {
            Instant.ofEpochMilli(timestampMs).toString()
        } catch (e: Exception) {
            timestampMs.toString()
        }
        val code = httpCode?.let { " http $it" } ?: ""
        return when (result) {
            PollResult.OK -> "ok$code at $at"
            PollResult.AUTH_FAILED ->
                "auth-failed-attempt-$consecutiveAuthFailures-of-" +
                    "${PollAuthPolicy.MAX_CONSECUTIVE_AUTH_FAILURES}$code at $at"
            PollResult.NON_AUTH_ERROR -> "network-error$code at $at"
            PollResult.NO_CREDENTIALS -> "not-paired at $at"
        }
    }
}
