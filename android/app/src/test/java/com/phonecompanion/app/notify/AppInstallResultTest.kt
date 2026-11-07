package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInstallResultTest {

    @Test
    fun `success maps to Installed`() {
        assertEquals(
            AppInstallResult.Outcome.Installed,
            AppInstallResult.classify(AppInstallResult.STATUS_SUCCESS, null),
        )
    }

    @Test
    fun `user abort maps to UserCancelled with no retry`() {
        assertEquals(
            AppInstallResult.Outcome.UserCancelled,
            AppInstallResult.classify(AppInstallResult.STATUS_FAILURE_ABORTED, "abort"),
        )
    }

    @Test
    fun `failures map to Failed carrying the installer message`() {
        for (status in listOf(
            AppInstallResult.STATUS_FAILURE,
            AppInstallResult.STATUS_FAILURE_BLOCKED,
            AppInstallResult.STATUS_FAILURE_CONFLICT,
            AppInstallResult.STATUS_FAILURE_INCOMPATIBLE,
            AppInstallResult.STATUS_FAILURE_INVALID,
            AppInstallResult.STATUS_FAILURE_STORAGE,
        )) {
            val outcome = AppInstallResult.classify(status, "msg-$status")
            assertTrue(outcome is AppInstallResult.Outcome.Failed)
            assertEquals("msg-$status", (outcome as AppInstallResult.Outcome.Failed).statusMessage)
        }
    }

    @Test
    fun `unknown and pending codes fail closed`() {
        assertTrue(
            AppInstallResult.classify(AppInstallResult.STATUS_PENDING, null)
                is AppInstallResult.Outcome.Failed,
        )
        assertTrue(
            AppInstallResult.classify(999, "weird") is AppInstallResult.Outcome.Failed,
        )
    }
}
