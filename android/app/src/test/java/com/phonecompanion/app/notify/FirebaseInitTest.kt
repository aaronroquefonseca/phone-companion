package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Firebase init fix: PairActivity.fetchFcmToken() used to call
 * FirebaseMessaging with no FirebaseApp ever initialized (the
 * google-services plugin is intentionally not applied), so the
 * IllegalStateException was swallowed by the catch-all and every enroll
 * sent an empty fcm_token. FirebaseInit.ensure() is the single gate before
 * the first FirebaseMessaging use: idempotent, fail-closed, and logging
 * init failure distinctly from Play-services-unreachable.
 */
class FirebaseInitTest {

    @Test
    fun `ensure is idempotent, second call does not re-initialize`() {
        var initialized = false
        var initCalls = 0
        val logged = mutableListOf<String>()
        val ensureTwice = {
            FirebaseInit.ensure(
                alreadyInitialized = { initialized },
                doInit = {
                    initCalls++
                    initialized = true
                },
                configured = true,
                log = { logged.add(it) },
            )
        }
        assertTrue(ensureTwice())
        assertTrue(ensureTwice())
        assertEquals(1, initCalls)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `ensure fails closed without config, never initializes`() {
        var initCalls = 0
        val logged = mutableListOf<String>()
        val result = FirebaseInit.ensure(
            alreadyInitialized = { false },
            doInit = { initCalls++ },
            configured = false,
            log = { logged.add(it) },
        )
        assertFalse(result)
        assertEquals(0, initCalls)
        assertEquals(listOf(FirebaseInit.LOG_INIT_FAILED), logged)
    }

    @Test
    fun `ensure fails closed when init throws, logs init branch without leaking`() {
        val logged = mutableListOf<String>()
        val result = FirebaseInit.ensure(
            alreadyInitialized = { false },
            doInit = { throw IllegalStateException("FirebaseApp not initialized") },
            configured = true,
            log = { logged.add(it) },
        )
        assertFalse(result)
        assertEquals(1, logged.size)
        // Init-failure branch, carrying only the exception class name —
        // never a message that could echo config material.
        assertTrue(logged[0].startsWith(FirebaseInit.LOG_INIT_FAILED))
        assertTrue(logged[0].contains("IllegalStateException"))
    }

    @Test
    fun `already-initialized app returns true without touching config or init`() {
        var initCalls = 0
        val logged = mutableListOf<String>()
        val result = FirebaseInit.ensure(
            alreadyInitialized = { true },
            doInit = { initCalls++ },
            configured = false,
            log = { logged.add(it) },
        )
        assertTrue(result)
        assertEquals(0, initCalls)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `ensure logs init-failed when init leaves no app registered`() {
        var initCalls = 0
        val logged = mutableListOf<String>()
        val result = FirebaseInit.ensure(
            alreadyInitialized = { false },
            doInit = { initCalls++ },
            configured = true,
            log = { logged.add(it) },
        )
        assertFalse(result)
        assertEquals(1, initCalls)
        assertEquals(listOf(FirebaseInit.LOG_INIT_FAILED), logged)
    }

    @Test
    fun `isConfigured rejects blanks and placeholders, accepts full config`() {
        assertFalse(FirebaseInit.isConfigured("", "p", "a", "k"))
        assertFalse(
            FirebaseInit.isConfigured(
                FirebaseInit.PLACEHOLDER,
                FirebaseInit.PLACEHOLDER,
                FirebaseInit.PLACEHOLDER,
                FirebaseInit.PLACEHOLDER,
            ),
        )
        assertFalse(FirebaseInit.isConfigured("sender", FirebaseInit.PLACEHOLDER, "a", "k"))
        assertTrue(FirebaseInit.isConfigured("sender", "project", "app-id", "api-key"))
    }

    @Test
    fun `init-failed and play-unreachable log branches are distinct strings`() {
        assertNotEquals(FirebaseInit.LOG_INIT_FAILED, FirebaseInit.LOG_PLAY_UNREACHABLE)
        assertTrue(FirebaseInit.LOG_INIT_FAILED.contains("init failed"))
        assertTrue(FirebaseInit.LOG_PLAY_UNREACHABLE.contains("unreachable"))
        // Neither branch may carry config values: both are constants with
        // no room for keys, tokens, or sender fields.
        for (line in listOf(FirebaseInit.LOG_INIT_FAILED, FirebaseInit.LOG_PLAY_UNREACHABLE)) {
            assertFalse(line.contains(FirebaseInit.PLACEHOLDER))
            assertFalse(line.contains("key="))
            assertFalse(line.contains("token="))
        }
    }
}
