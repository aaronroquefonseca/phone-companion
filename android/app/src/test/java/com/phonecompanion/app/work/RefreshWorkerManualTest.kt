package com.phonecompanion.app.work

import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the manual-refresh request shape: expedited with a
 * run-as-normal fallback.
 *
 * NOTE: `androidx.work:work-testing` is deliberately NOT a dependency
 * (no new test deps for this change), so instead of driving a
 * TestDriver this builds the real request via
 * [RefreshWorker.manualRefreshRequest] and asserts on its WorkSpec
 * directly. A quiet refactor back to a deferrable one-shot would
 * re-hide the "Refresh now lies on Samsung-class OEMs" bug — this test
 * fails first.
 */
class RefreshWorkerManualTest {

    @Test
    fun `manual refresh is expedited with non-expedited fallback`() {
        val request = RefreshWorker.manualRefreshRequest()
        assertTrue(
            "manual refresh must be expedited (user taps run now)",
            request.workSpec.expedited,
        )
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            request.workSpec.outOfQuotaPolicy,
        )
    }
}
