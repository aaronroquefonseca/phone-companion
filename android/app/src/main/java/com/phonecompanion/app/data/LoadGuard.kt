package com.phonecompanion.app.data

import kotlinx.coroutines.CancellationException

/**
 * Runs [block], falling back to [fallback] on any [Exception].
 *
 * Coroutine [CancellationException] is always rethrown: it subclasses
 * [Exception], so a bare `catch (e: Exception)` would otherwise swallow
 * cancellation and break structured concurrency (stuck workers, leaked
 * polls). Every widget load path and the poll worker go through here.
 */
suspend fun <T> loadOrFallback(fallback: T, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
}
