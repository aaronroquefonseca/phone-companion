package com.phonecompanion.app.work

/**
 * Persisted widget-poll auth state.
 *
 * Plain (non-encrypted) storage on purpose: the consecutive-401/403
 * streak must survive credential state — it is read to decide whether to
 * clear credentials — and neither the counter nor the last [PollStatus]
 * is secret (HTTP code + outcome + timestamp only, never tokens/bodies).
 * Android persistence lives in `SharedPrefsPollStateStore`; this file
 * stays framework-free so the policy is JVM-testable.
 */
interface PollStateStore {
    /** Consecutive 401/403 count for the current credential set. */
    var consecutiveAuthFailures: Int

    /** Last poll outcome, null before the first recorded poll. */
    var lastStatus: PollStatus?
}

/** Test/fallback [PollStateStore] with no persistence. */
class InMemoryPollStateStore(
    override var consecutiveAuthFailures: Int = 0,
    override var lastStatus: PollStatus? = null,
) : PollStateStore
