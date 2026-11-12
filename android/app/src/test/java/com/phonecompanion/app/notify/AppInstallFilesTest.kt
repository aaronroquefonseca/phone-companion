package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the stale-APK purge rule: on prompt/activity entry, entries not
 * matching the current push id — or older than the documented retention —
 * are deleted; the fresh current entry survives.
 */
class AppInstallFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder("app_push")

    @Test
    fun `non-matching entries are purged, current entry kept`() {
        val d = dir()
        val keep = File(d, AppInstallPayload.cacheFileNameFor("current-push"))
        keep.writeBytes(ByteArray(4))
        val stale = File(d, AppInstallPayload.cacheFileNameFor("old-push"))
        stale.writeBytes(ByteArray(4))
        val deleted = AppInstallFiles.purgeStale(d, "current-push")
        assertEquals(1, deleted)
        assertTrue(keep.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `current entry older than retention is purged too`() {
        val d = dir()
        val old = File(d, AppInstallPayload.cacheFileNameFor("current-push"))
        old.writeBytes(ByteArray(4))
        assertTrue(old.setLastModified(System.currentTimeMillis() - AppInstallFiles.STALE_APK_MAX_AGE_MS - 1_000))
        val deleted = AppInstallFiles.purgeStale(d, "current-push")
        assertEquals(1, deleted)
        assertFalse(old.exists())
    }

    @Test
    fun `fresh current entry survives and empty dir is a no-op`() {
        val d = dir()
        val fresh = File(d, AppInstallPayload.cacheFileNameFor("current-push"))
        fresh.writeBytes(ByteArray(4))
        assertEquals(0, AppInstallFiles.purgeStale(d, "current-push"))
        assertTrue(fresh.exists())
        assertEquals(0, AppInstallFiles.purgeStale(File(d, "absent"), "current-push"))
    }

    @Test
    fun `traversal push ids map inside the dir`() {
        // cacheFileNameFor sanitizes: the purge keep-name can never escape.
        val name = AppInstallPayload.cacheFileNameFor("../../evil")
        assertFalse(name.contains("/"))
    }

    @Test
    fun `in-flight files are never purged even when superseded`() {
        // A live flow for push-a must survive a purge scoped to push-b.
        val d = dir()
        val live = File(d, AppInstallPayload.cacheFileNameFor("push-a"))
        live.writeBytes(ByteArray(4))
        val deleted = AppInstallFiles.purgeStale(
            dir = d,
            currentPushId = "push-b",
            protectedFileNames = setOf(AppInstallPayload.cacheFileNameFor("push-a")),
        )
        assertEquals(0, deleted)
        assertTrue(live.exists())
    }

    @Test
    fun `sweep deletes only stale entries and never a protected one`() {
        val d = dir()
        val old = File(d, AppInstallPayload.cacheFileNameFor("old"))
        old.writeBytes(ByteArray(4))
        assertTrue(old.setLastModified(System.currentTimeMillis() - AppInstallFiles.STALE_APK_MAX_AGE_MS - 1_000))
        val fresh = File(d, AppInstallPayload.cacheFileNameFor("fresh"))
        fresh.writeBytes(ByteArray(4))
        val protected = File(d, AppInstallPayload.cacheFileNameFor("live"))
        protected.writeBytes(ByteArray(4))
        val deleted = AppInstallFiles.sweepDir(
            d,
            protectedFileNames = setOf(AppInstallPayload.cacheFileNameFor("live")),
        )
        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(protected.exists())
    }

    @Test
    fun `sweep keeps every fresh entry and tolerates an absent dir`() {
        val d = dir()
        val fresh = File(d, AppInstallPayload.cacheFileNameFor("fresh"))
        fresh.writeBytes(ByteArray(4))
        assertEquals(0, AppInstallFiles.sweepDir(d))
        assertTrue(fresh.exists())
        assertEquals(0, AppInstallFiles.sweepDir(File(d, "absent")))
    }
}
