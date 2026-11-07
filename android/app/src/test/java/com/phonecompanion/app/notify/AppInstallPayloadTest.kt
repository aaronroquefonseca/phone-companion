package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInstallPayloadTest {

    private fun valid(): Map<String, String> = mapOf(
        "type" to "app_install",
        "push_id" to "push-1",
        "apk_url" to "https://example.com/app.apk",
        "sha256" to "a".repeat(64),
        "package" to "com.example.app",
        "version" to "2.1",
        "label" to "Beta",
    )

    @Test
    fun `valid payload parses`() {
        val p = AppInstallPayload.fromMap(valid())
        assertNotNull(p)
        p!!
        assertEquals("push-1", p.pushId)
        assertEquals("https://example.com/app.apk", p.apkUrl)
        assertEquals("a".repeat(64), p.sha256)
        assertEquals("com.example.app", p.packageName)
        assertEquals("2.1", p.version)
        assertEquals("Beta", p.label)
        assertEquals("2.1", p.displayVersion())
        assertEquals("a".repeat(12) + "…", p.shortSha())
    }

    @Test
    fun `version is optional and blank means unknown version`() {
        val noVersion = AppInstallPayload.fromMap(valid() - "version")
        assertNotNull(noVersion)
        assertEquals("unknown version", noVersion!!.displayVersion())

        val blankVersion = AppInstallPayload.fromMap(valid() + ("version" to "  "))
        // Blank trims to absent: still valid, still unknown version.
        assertNotNull(blankVersion)
        assertEquals("unknown version", blankVersion!!.displayVersion())
    }

    @Test
    fun `discriminator accepts type and legacy kind`() {
        assertTrue(AppInstallPayload.isAppInstall(mapOf("type" to "app_install")))
        assertTrue(AppInstallPayload.isAppInstall(mapOf("kind" to "app_install")))
        assertFalse(AppInstallPayload.isAppInstall(mapOf("kind" to "note")))
        assertFalse(AppInstallPayload.isAppInstall(mapOf("type" to "note")))
        assertFalse(AppInstallPayload.isAppInstall(emptyMap()))
    }

    @Test
    fun `missing required fields are rejected`() {
        for (key in listOf("push_id", "apk_url", "sha256", "package", "label")) {
            assertNull(key, AppInstallPayload.fromMap(valid() - key))
        }
        // Blank-only values are missing too.
        for (key in listOf("push_id", "package", "label")) {
            assertNull("blank $key", AppInstallPayload.fromMap(valid() + (key to "   ")))
        }
        // Wrong discriminator is not an install payload at all.
        assertFalse(AppInstallPayload.isAppInstall(valid() - "type"))
    }

    @Test
    fun `bad URL schemes are rejected`() {
        for (bad in listOf(
            "http://example.com/app.apk",
            "ftp://example.com/app.apk",
            "https://",
            "https:///path-only",
            "",
            "not a url",
            "//example.com/app.apk",
        )) {
            assertNull(bad, AppInstallPayload.fromMap(valid() + ("apk_url" to bad)))
        }
    }

    @Test
    fun `bad sha256 shapes are rejected`() {
        for (bad in listOf(
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64), // uppercase: contract is lowercase hex
            "g".repeat(64),
            "",
            "not-a-hash",
        )) {
            assertNull(bad, AppInstallPayload.fromMap(valid() + ("sha256" to bad)))
        }
    }

    @Test
    fun `bad package shapes are rejected`() {
        for (bad in listOf(
            "",
            "singleword",
            ".leading.dot",
            "trailing.dot.",
            "has space.com",
            "has-dash-start.1com",
        )) {
            assertNull(bad, AppInstallPayload.fromMap(valid() + ("package" to bad)))
        }
    }

    @Test
    fun `oversized fields are rejected not truncated`() {
        assertNull(AppInstallPayload.fromMap(valid() + ("push_id" to "p".repeat(129))))
        assertNull(AppInstallPayload.fromMap(valid() + ("label" to "l".repeat(201))))
        assertNull(AppInstallPayload.fromMap(valid() + ("version" to "v".repeat(65))))
        assertNull(AppInstallPayload.fromMap(valid() + ("package" to ("a".repeat(254) + ".b"))))
        assertNull(
            AppInstallPayload.fromMap(
                valid() + ("apk_url" to ("https://example.com/" + "x".repeat(2048))),
            ),
        )
    }

    @Test
    fun `never crashes on hostile input`() {
        // Nulls are impossible from FCM (Map<String, String>), but empty and
        // extreme maps must still return null rather than throw.
        assertNull(AppInstallPayload.fromMap(emptyMap()))
        assertNull(AppInstallPayload.fromMap(mapOf("type" to "app_install")))
        val huge = valid() + ("label" to "x".repeat(100000))
        assertNull(AppInstallPayload.fromMap(huge))
    }

    @Test
    fun `notify id is stable per push id`() {
        val a = AppInstallPayload.fromMap(valid())!!
        val b = AppInstallPayload.fromMap(valid() + ("label" to "Other"))!!
        assertEquals(a.notifyId(), b.notifyId())
        val c = AppInstallPayload.fromMap(valid() + ("push_id" to "push-2"))!!
        assertTrue(a.notifyId() != c.notifyId())
    }

    @Test
    fun `cache file name is traversal-safe`() {
        val p = AppInstallPayload.fromMap(valid() + ("push_id" to "../../evil/x"))!!
        val name = p.cacheFileName()
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
        assertTrue(name.endsWith(".apk"))
        assertTrue(name.startsWith("app-push-"))
    }
}
