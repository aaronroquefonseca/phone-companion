package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCodeTest {

    @Test
    fun `tap and action codes differ for the same notification`() {
        val notifyId = 42
        val tap = CompanionMessagingService.tapRequestCode(notifyId)
        val action = CompanionMessagingService.actionRequestCode(notifyId, "ack")
        assertNotEquals(tap, action)
    }

    @Test
    fun `distinct actions get distinct codes`() {
        val notifyId = 42
        val codes = setOf("ack", "snooze", "open").map {
            CompanionMessagingService.actionRequestCode(notifyId, it)
        }.toSet()
        assertEquals(3, codes.size)
    }

    @Test
    fun `codes are stable across calls`() {
        repeat(3) {
            assertEquals(
                CompanionMessagingService.tapRequestCode(7),
                CompanionMessagingService.tapRequestCode(7),
            )
            assertEquals(
                CompanionMessagingService.actionRequestCode(7, "ack"),
                CompanionMessagingService.actionRequestCode(7, "ack"),
            )
        }
    }

    @Test
    fun `no notifyId-times-4-plus-index collisions`() {
        // Under the old scheme, action index 0 of notifyId 4 collided with
        // the tap code of notifyId 16 (4*4+0 == 16), and every tap equaled
        // some other notification's action-0 code. The new domains must not
        // reproduce that aliasing across a sweep of ids and indices.
        val notifyIds = (0..64).toList()
        val taps = notifyIds.map { CompanionMessagingService.tapRequestCode(it) }.toSet()
        val actions = notifyIds.flatMap { id ->
            (0..2).map { CompanionMessagingService.actionRequestCode(id, it) }
        }
        val overlap = taps.intersect(actions.toSet())
        assertTrue("tap/action overlap: $overlap", overlap.isEmpty())
        // Id-based overload stays distinct per action id too.
        val idBased = notifyIds.flatMap { id ->
            listOf("a", "b").map { CompanionMessagingService.actionRequestCode(id, it) }
        }
        assertTrue(idBased.intersect(taps).isEmpty())
    }

    @Test
    fun `action codes bind the action id, not its position`() {
        // Reordering actions must not move their PendingIntents.
        assertEquals(
            CompanionMessagingService.actionRequestCode(9, "ack"),
            CompanionMessagingService.actionRequestCode(9, "ack"),
        )
        assertNotEquals(
            CompanionMessagingService.actionRequestCode(9, "ack"),
            CompanionMessagingService.actionRequestCode(9, "snooze"),
        )
    }
}
