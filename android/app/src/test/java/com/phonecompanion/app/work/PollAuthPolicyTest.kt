package com.phonecompanion.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the consecutive-auth-failure policy: a single 401/403 only
 * advances the streak, the wipe needs N=2 consecutive auth failures, a
 * success resets, and non-auth outcomes leave the streak untouched.
 */
class PollAuthPolicyTest {

    @Test
    fun `wipe threshold is two consecutive auth failures`() {
        assertEquals(2, PollAuthPolicy.MAX_CONSECUTIVE_AUTH_FAILURES)
    }

    @Test
    fun `auth failure codes are exactly 401 and 403`() {
        assertTrue(PollAuthPolicy.isAuthFailureCode(401))
        assertTrue(PollAuthPolicy.isAuthFailureCode(403))
    }

    @Test
    fun `other codes are not auth failures`() {
        for (code in listOf(200, 201, 304, 400, 404, 409, 410, 422, 429, 500, 503)) {
            assertFalse("code $code", PollAuthPolicy.isAuthFailureCode(code))
        }
    }

    @Test
    fun `success resets the streak`() {
        assertEquals(0, PollAuthPolicy.nextConsecutiveAuthFailures(1, success = true, authFailure = false))
        assertEquals(0, PollAuthPolicy.nextConsecutiveAuthFailures(0, success = true, authFailure = false))
    }

    @Test
    fun `auth failure increments the streak`() {
        assertEquals(1, PollAuthPolicy.nextConsecutiveAuthFailures(0, success = false, authFailure = true))
        assertEquals(2, PollAuthPolicy.nextConsecutiveAuthFailures(1, success = false, authFailure = true))
    }

    @Test
    fun `non-auth outcome leaves the streak untouched`() {
        assertEquals(0, PollAuthPolicy.nextConsecutiveAuthFailures(0, success = false, authFailure = false))
        assertEquals(1, PollAuthPolicy.nextConsecutiveAuthFailures(1, success = false, authFailure = false))
    }

    @Test
    fun `wipe only at or above threshold`() {
        assertFalse(PollAuthPolicy.shouldWipe(0))
        assertFalse(PollAuthPolicy.shouldWipe(1))
        assertTrue(PollAuthPolicy.shouldWipe(2))
        assertTrue(PollAuthPolicy.shouldWipe(3))
    }
}
