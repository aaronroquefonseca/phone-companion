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
        "command_id" to "cmd-1",
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
        assertEquals("cmd-1", p.commandId)
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
        for (key in listOf("command_id", "push_id", "apk_url", "sha256", "package", "label")) {
            assertNull(key, AppInstallPayload.fromMap(valid() - key))
        }
        // Blank-only values are missing too.
        for (key in listOf("command_id", "push_id", "package", "label")) {
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
        assertNull(AppInstallPayload.fromMap(valid() + ("command_id" to "c".repeat(129))))
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
        // Nulls are impossible from the stringified command map, but empty
        // and extreme maps must still return null rather than throw.
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

    @Test
    fun `prompt extras round-trip through fromExtras with intent keys`() {
        // promptIntent writes exactly toExtras(); the activity reads via
        // fromExtras. Before the fix these key domains disagreed and every
        // activity launch parsed to null (Install unreachable).
        val original = AppInstallPayload.fromMap(valid())!!
        val roundTripped = AppInstallPayload.fromExtras(original.toExtras())
        assertNotNull(roundTripped)
        assertEquals(original, roundTripped)
    }

    @Test
    fun `fromExtras accepts wire spellings too`() {
        val roundTripped = AppInstallPayload.fromExtras(valid())
        assertNotNull(roundTripped)
        assertEquals("push-1", roundTripped!!.pushId)
        assertEquals("Beta", roundTripped.label)
    }

    @Test
    fun `fromExtras prefers intent keys and rejects tampered bundles`() {
        val original = AppInstallPayload.fromMap(valid())!!
        // Tampered URL in intent spelling: re-validation rejects.
        assertNull(
            AppInstallPayload.fromExtras(
                original.toExtras() + (AppInstallPayload.EXTRA_APK_URL to "http://evil.example/x.apk"),
            ),
        )
        // Missing key in both spellings: rejected, never trusted.
        assertNull(AppInstallPayload.fromExtras(original.toExtras() - AppInstallPayload.EXTRA_SHA256))
    }

    @Test
    fun `attempt nonce round-trips outside the validated payload`() {
        val extras = AppInstallPayload.fromMap(valid())!!.toExtras() +
            (AppInstallPayload.EXTRA_ATTEMPT to "nonce-1")
        assertEquals("nonce-1", AppInstallPayload.attemptFromExtras(extras))
        // No attempt on the fresh prompt tap.
        assertNull(AppInstallPayload.attemptFromExtras(AppInstallPayload.fromMap(valid())!!.toExtras()))
    }

    @Test
    fun `label and version with newlines are single-line sanitized`() {
        val p = AppInstallPayload.fromMap(
            valid() + ("label" to "Beta\nEvil line") + ("version" to "2.1\rcarriage"),
        )
        assertNotNull(p)
        assertFalse(p!!.label.contains("\n"))
        assertFalse(p.version!!.contains("\r"))
        assertEquals("Beta Evil line", p.label)
    }

    @Test
    fun `bidi and format controls are stripped from consent text`() {
        val p = AppInstallPayload.fromMap(
            valid() + ("label" to "Be\u202Eta") + ("version" to "1\u200E.0"),
        )
        assertNotNull(p)
        assertEquals("Beta", p!!.label)
        assertEquals("1.0", p.version)
    }

    @Test
    fun `label that is only controls is rejected as blank`() {
        assertNull(AppInstallPayload.fromMap(valid() + ("label" to "\u202E\u200E")))
    }
}
