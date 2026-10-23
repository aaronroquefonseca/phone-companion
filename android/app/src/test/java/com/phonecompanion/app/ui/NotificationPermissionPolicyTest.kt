package com.phonecompanion.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionPolicyTest {

    @Test
    fun `unpaired never prompts`() {
        assertEquals(
            NotificationPermissionPolicy.PromptState.HIDDEN,
            NotificationPermissionPolicy.decide(
                paired = false,
                granted = false,
                deniedBefore = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `granted shows nothing`() {
        assertEquals(
            NotificationPermissionPolicy.PromptState.GRANTED,
            NotificationPermissionPolicy.decide(
                paired = true,
                granted = true,
                deniedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `first paired ask shows rationale`() {
        assertEquals(
            NotificationPermissionPolicy.PromptState.RATIONALE,
            NotificationPermissionPolicy.decide(
                paired = true,
                granted = false,
                deniedBefore = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `repeat ask after soft denial shows rationale again`() {
        assertEquals(
            NotificationPermissionPolicy.PromptState.RATIONALE,
            NotificationPermissionPolicy.decide(
                paired = true,
                granted = false,
                deniedBefore = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `hard denial shows persistent settings line`() {
        assertEquals(
            NotificationPermissionPolicy.PromptState.DENIED,
            NotificationPermissionPolicy.decide(
                paired = true,
                granted = false,
                deniedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }
}
