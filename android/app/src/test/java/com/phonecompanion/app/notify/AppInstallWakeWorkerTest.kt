package com.phonecompanion.app.notify

import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the FCM-wake request shape: expedited with a run-as-normal
 * fallback, so the silent wake really polls NOW.
 *
 * NOTE: `androidx.work:work-testing` is deliberately NOT a dependency
 * (no new test deps for this change), so instead of driving a
 * TestDriver this builds the real request via
 * [AppInstallWakeWorker.wakeRequest] and asserts on its WorkSpec
 * directly. The service-side branch itself (wake → enqueue, no render)
 * needs a device/emulator and is covered by the manual checklist, not
 * here.
 */
class AppInstallWakeWorkerTest {

    @Test
    fun `wake poll is expedited with non-expedited fallback`() {
        val request = AppInstallWakeWorker.wakeRequest()
        assertTrue(
            "wake poll must be expedited (instant delivery, not OEM-deferred)",
            request.workSpec.expedited,
        )
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            request.workSpec.outOfQuotaPolicy,
        )
    }
}
