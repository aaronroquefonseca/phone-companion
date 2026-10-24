package com.phonecompanion.app.ui

import com.phonecompanion.app.work.PollResult
import com.phonecompanion.app.work.PollStatus
import java.time.Instant

/**
 * Pure copy mapping for the revocation marker
 * ([com.phonecompanion.app.work.PollStateStore.revokedAtMs]).
 *
 * Framework-free so both launcher and list copy stay JVM-testable;
 * activities only resolve string resources / bind these lines. The marker
 * distinguishes "revoked server-side — scan a new code" from
 * "never paired", which the raw poll status alone cannot: after the wipe
 * the next poll overwrites the status with `NO_CREDENTIALS`.
 */
object RevocationCopy {

    /**
     * Launcher line when unpaired: the revoked copy when the wipe marker
     * is set, the generic never-paired copy otherwise. Resource strings
     * are passed in so this stays testable without Android.
     */
    fun mainUnpairedText(revokedAtMs: Long?, notPairedText: String, revokedText: String): String =
        if (revokedAtMs != null) revokedText else notPairedText

    /**
     * Widget-list status line with the revocation marker applied. A set
     * marker plus a missing/`NO_CREDENTIALS` status renders the revoked
     * line (with the wipe time); any other status renders unchanged, so a
     * paired device never shows stale revocation copy.
     */
    fun pollStatusLine(status: PollStatus?, revokedAtMs: Long?): String {
        if (revokedAtMs != null && (status == null || status.result == PollResult.NO_CREDENTIALS)) {
            return "revoked at ${formatAt(revokedAtMs)} — scan a new code to reconnect"
        }
        return status?.displayText() ?: "never-polled"
    }

    private fun formatAt(revokedAtMs: Long): String = try {
        Instant.ofEpochMilli(revokedAtMs).toString()
    } catch (e: Exception) {
        revokedAtMs.toString()
    }
}
