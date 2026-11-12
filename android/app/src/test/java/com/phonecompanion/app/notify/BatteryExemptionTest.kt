package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery-exemption row logic ([BatteryExemption]): the visibility
 * decision is pure boolean logic and JVM-tested (granted vs not). The
 * Android edges (`isExempt` via PowerManager, `requestIntent`) need a
 * device and stay on the manual checklist.
 */
class BatteryExemptionTest {

    @Test
    fun `granted exemption maps to unrestricted with no fix button`() {
        assertEquals(BatteryExemption.Status.UNRESTRICTED, BatteryExemption.statusOf(true))
        assertFalse(BatteryExemption.shouldOfferFix(true))
    }

    @Test
    fun `missing exemption maps to restricted with fix button`() {
        assertEquals(BatteryExemption.Status.RESTRICTED, BatteryExemption.statusOf(false))
        assertTrue(BatteryExemption.shouldOfferFix(false))
    }
}
