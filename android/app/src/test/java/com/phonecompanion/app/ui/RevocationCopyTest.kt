package com.phonecompanion.app.ui

import com.phonecompanion.app.work.PollResult
import com.phonecompanion.app.work.PollStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revocation copy: the launcher distinguishes "revoked — scan a new code"
 * from the generic never-paired line, and the widget list surfaces the
 * wipe in its status/empty-state text instead of a silent empty state.
 */
class RevocationCopyTest {

    private companion object {
        const val NOT_PAIRED = "Not paired yet — open Pair to scan the WebUI QR."
        const val REVOKED = "This device was revoked — scan a new code to reconnect"
        const val WIPED_AT = 1_700_000_000_000L
    }

    private fun noCredentialsStatus() = PollStatus(
        result = PollResult.NO_CREDENTIALS,
        httpCode = null,
        timestampMs = WIPED_AT,
        consecutiveAuthFailures = 0,
    )

    @Test
    fun `launcher shows revoked copy only when the marker is set`() {
        assertEquals(
            REVOKED,
            RevocationCopy.mainUnpairedText(WIPED_AT, NOT_PAIRED, REVOKED),
        )
        assertEquals(
            NOT_PAIRED,
            RevocationCopy.mainUnpairedText(null, NOT_PAIRED, REVOKED),
        )
    }

    @Test
    fun `list status names revocation when unpaired with the marker set`() {
        val text = WidgetListUi.statusText(noCredentialsStatus(), WIPED_AT)
        assertTrue(text.contains("revoked"))
        assertTrue(text.contains("scan a new code"))

        val missing = WidgetListUi.statusText(null, WIPED_AT)
        assertTrue(missing.contains("revoked"))
    }

    @Test
    fun `list status keeps the generic never-paired marker without revocation`() {
        assertEquals("never-polled", WidgetListUi.statusText(null, null))
        assertTrue(
            WidgetListUi.statusText(noCredentialsStatus(), null).contains("not-paired"),
        )
    }

    @Test
    fun `paired status never shows stale revocation copy`() {
        val ok = PollStatus(PollResult.OK, 200, WIPED_AT, 0)
        assertTrue(WidgetListUi.statusText(ok, WIPED_AT).contains("ok"))
        val authFailed = PollStatus(PollResult.AUTH_FAILED, 401, WIPED_AT, 1)
        assertTrue(
            WidgetListUi.statusText(authFailed, null).contains("auth-failed-attempt-1-of-2"),
        )
    }

    @Test
    fun `empty state carries revocation for both wiped and never-polled combos`() {
        val revoked = WidgetListUi.emptyStateText(noCredentialsStatus(), WIPED_AT)
        assertTrue(revoked.contains("No cached widgets"))
        assertTrue(revoked.contains("revoked"))

        val neverPaired = WidgetListUi.emptyStateText(null, null)
        assertTrue(neverPaired.contains("No cached widgets"))
        assertTrue(neverPaired.contains("never-polled"))
    }

    @Test
    fun `non-positive markers normalize to unset like the store`() {
        assertEquals(
            NOT_PAIRED,
            RevocationCopy.mainUnpairedText(0L, NOT_PAIRED, REVOKED),
        )
        assertEquals(
            NOT_PAIRED,
            RevocationCopy.mainUnpairedText(-5L, NOT_PAIRED, REVOKED),
        )
        assertEquals("never-polled", WidgetListUi.statusText(null, 0L))
        assertTrue(
            WidgetListUi.statusText(noCredentialsStatus(), -5L).contains("not-paired"),
        )
    }
}
