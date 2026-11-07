package com.phonecompanion.app.notify

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide download cancellation flags, keyed by push id.
 *
 * Pure JVM (zero Android imports): the cancel path is unit-testable. The
 * progress notification's Cancel action targets [AppInstallCancelReceiver],
 * which sets the flag; the download loop ([AppInstallTransfer.copyCapped]
 * via the downloader's [isCancelled]) observes it between buffer fills.
 * Flags are cleared when the transfer terminates (any outcome) so a later
 * push with the same id starts un-cancelled.
 */
object AppInstallCancels {
    private val flags = ConcurrentHashMap<String, AtomicBoolean>()

    /** Returns true when cancellation was requested for [pushId]. */
    fun isCancelled(pushId: String): Boolean = flags[pushId]?.get() == true

    /** Requests cancellation for [pushId] (idempotent). */
    fun requestCancel(pushId: String) {
        flags.getOrPut(pushId) { AtomicBoolean(false) }.set(true)
    }

    /** Clears the flag for [pushId] (call on every terminal state). */
    fun clear(pushId: String) {
        flags.remove(pushId)
    }
}
