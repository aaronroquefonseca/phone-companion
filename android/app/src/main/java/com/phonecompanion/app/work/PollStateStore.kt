package com.phonecompanion.app.work

/**
 * Persisted widget-poll auth state.
 *
 * Plain (non-encrypted) storage on purpose: the consecutive-401/403
 * streak must survive credential state — it is read to decide whether to
 * clear credentials — and neither the counter, the last [PollStatus], nor
 * the revocation marker is secret (HTTP code + outcome + timestamps only,
 * never tokens/bodies). Android persistence lives in
 * `SharedPrefsPollStateStore`; this file stays framework-free so the
 * policy is JVM-testable.
 */
interface PollStateStore {
    /** Consecutive 401/403 count for the current credential set. */
    var consecutiveAuthFailures: Int

    /** Last poll outcome, null before the first recorded poll. */
    var lastStatus: PollStatus?

    /**
     * Revocation marker: `System.currentTimeMillis()` stamped when the
     * consecutive-auth-failure wipe fires ([WidgetPoller.handleRevoked]),
     * null otherwise. Plain pref next to the poll state — deliberately NOT
     * encrypted storage so it survives the credential wipe. Cleared on
     * successful enroll/pair and on explicit unpair; the poller itself
     * never clears it (a later successful poll implies a re-pair, which
     * clears it at the enroll seam).
     */
    var revokedAtMs: Long?
}

/** Test/fallback [PollStateStore] with no persistence. */
class InMemoryPollStateStore(
    override var consecutiveAuthFailures: Int = 0,
    override var lastStatus: PollStatus? = null,
    override var revokedAtMs: Long? = null,
) : PollStateStore
