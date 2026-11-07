package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Posted-ids persistence (plain prefs, pruned).
 *
 * The set suppresses duplicate prompts when the server re-offers a
 * command (app died before acking, ack lost). These tests pin the pure
 * prune/encode/decode logic plus restart survival: a store recreated
 * over the same backing still suppresses.
 */
class AppInstallPostedStoreTest {

    /** Memory-backed store sharing one backing slot (simulates prefs). */
    private class MemoryPostedStore(private val disk: MutableMap<String, String>) :
        AppInstallPostedStore {
        override fun isPosted(pushId: String): Boolean {
            if (pushId.isBlank()) return false
            return AppInstallPostedIds.decode(disk[KEY]).contains(pushId)
        }

        override fun markPosted(pushIds: Collection<String>) {
            if (pushIds.isEmpty()) return
            val merged = AppInstallPostedIds.prune(AppInstallPostedIds.decode(disk[KEY]) + pushIds)
            disk[KEY] = AppInstallPostedIds.encode(merged)
        }

        companion object {
            const val KEY = "posted_push_ids"
        }
    }

    @Test
    fun `fresh id is not posted then is after marking`() {
        val disk = mutableMapOf<String, String>()
        val store = MemoryPostedStore(disk)
        assertFalse(store.isPosted("push-1"))
        store.markPosted(listOf("push-1"))
        assertTrue(store.isPosted("push-1"))
        assertFalse(store.isPosted("push-2"))
    }

    @Test
    fun `posted set survives a store restart over the same backing`() {
        val disk = mutableMapOf<String, String>()
        MemoryPostedStore(disk).markPosted(listOf("push-1", "push-2"))
        // "Restart": a fresh store instance over the same prefs backing.
        val restarted = MemoryPostedStore(disk)
        assertTrue(restarted.isPosted("push-1"))
        assertTrue(restarted.isPosted("push-2"))
        assertFalse(restarted.isPosted("push-3"))
    }

    @Test
    fun `prune dedupes and keeps the most recent cap`() {
        val pruned = AppInstallPostedIds.prune(listOf("a", "b", "a", "c"), max = 2)
        assertEquals(listOf("a", "c"), pruned)
        // Re-adding moves to the back (most-recent).
        val moved = AppInstallPostedIds.prune(listOf("a", "b", "c", "a"), max = 3)
        assertEquals(listOf("b", "c", "a"), moved)
    }

    @Test
    fun `prune drops blanks and caps at the named bound`() {
        val ids = (1..250).map { "push-$it" } + listOf("", "   ")
        val pruned = AppInstallPostedIds.prune(ids)
        assertEquals(AppInstallPostedIds.MAX_POSTED_IDS, pruned.size)
        assertEquals("push-250", pruned.last())
        assertEquals("push-51", pruned.first())
    }

    @Test
    fun `encode decode round-trips and decode tolerates garbage`() {
        val ids = listOf("push-1", "push-2")
        assertEquals(ids, AppInstallPostedIds.decode(AppInstallPostedIds.encode(ids)))
        assertTrue(AppInstallPostedIds.decode(null).isEmpty())
        assertTrue(AppInstallPostedIds.decode("").isEmpty())
        assertTrue(AppInstallPostedIds.decode("not json").isEmpty())
        // Moshi coerces JSON numbers to strings via nextString: tolerated
        // as opaque ids (validation rejects them downstream if unused).
        assertEquals(listOf("1", "2"), AppInstallPostedIds.decode("[1,2]"))
    }
}
