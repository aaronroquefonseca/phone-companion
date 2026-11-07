package com.phonecompanion.app.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Poll-triggered command orchestration (post-once + ack).
 *
 * Pins: fresh ids post then mark then ack; already-posted re-offers
 * skip the prompt but still ack (lost-ack convergence); prompt
 * failures are left unmarked AND unacked (server re-offers next poll);
 * ack failures keep the posted marks (no re-prompt) and retry next
 * poll; fetch failures never post/ack. The collaborators surface is
 * exactly fetch/post/ack — no widget, cache, or Glance edge exists for
 * this path to touch.
 */
class AppInstallCommandPollTest {

    private fun payload(pushId: String, commandId: String = "cmd-$pushId"): AppInstallPayload =
        AppInstallPayload.fromMap(
            mapOf(
                "type" to "app_install",
                "command_id" to commandId,
                "push_id" to pushId,
                "apk_url" to "https://example.com/$pushId.apk",
                "sha256" to "b".repeat(64),
                "package" to "com.example.app",
                "label" to "App $pushId",
            ),
        )!!

    private fun parsed(vararg ids: String) = AppInstallCommands.ParsedCommands(
        valid = ids.map(::payload),
        unknownKinds = 0,
        malformed = 0,
        syntaxError = false,
    )

    private class FakeDeps(
        var fetchOutcome: AppInstallCommandPoll.FetchOutcome,
        var ackResult: Boolean = true,
        var failPromptFor: Set<String> = emptySet(),
    ) : AppInstallCommandPoll.Collaborators {
        val prompted = mutableListOf<String>()
        val ackedBatches = mutableListOf<List<String>>()
        val logs = mutableListOf<String>()

        override suspend fun fetch(): AppInstallCommandPoll.FetchOutcome = fetchOutcome

        override suspend fun postPrompt(payload: AppInstallPayload) {
            if (payload.pushId in failPromptFor) throw IOException("prompt down")
            prompted.add(payload.pushId)
        }

        override suspend fun ack(ids: List<String>): Boolean {
            ackedBatches.add(ids.toList())
            return ackResult
        }
    }

    private class MemPosted : AppInstallPostedStore {
        private val ids = LinkedHashSet<String>()
        override fun isPosted(commandId: String): Boolean = commandId in ids
        override fun markPosted(commandIds: Collection<String>) {
            ids.addAll(commandIds.filter { it.isNotBlank() })
        }
    }

    private fun runPoll(
        deps: FakeDeps,
        posted: MemPosted = MemPosted(),
    ): AppInstallCommandPoll.PollResult = runBlocking {
        AppInstallCommandPoll.pollOnceWith(deps, posted) { deps.logs.add(it) }
    }

    @Test
    fun `fresh ids post mark and ack together`() = runBlocking {
        val deps = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1", "p2")))
        val posted = MemPosted()
        val result = AppInstallCommandPoll.pollOnceWith(deps, posted) { deps.logs.add(it) }

        assertEquals(listOf("p1", "p2"), deps.prompted)
        assertTrue(posted.isPosted("cmd-p1"))
        assertTrue(posted.isPosted("cmd-p2"))
        // The ack carries per-device command ids (what the server reads),
        // not push ids.
        assertEquals(listOf(listOf("cmd-p1", "cmd-p2")), deps.ackedBatches)
        assertEquals(2, result.offered)
        assertEquals(2, result.posted)
        assertEquals(0, result.skipped)
        assertTrue(result.acked)
    }

    @Test
    fun `already-posted re-offer skips the prompt but still acks`() {
        val posted = MemPosted()
        posted.markPosted(listOf("cmd-p1"))
        val deps = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1", "p2")))
        val result = runPoll(deps, posted)

        assertEquals(listOf("p2"), deps.prompted)
        assertEquals(1, result.skipped)
        assertEquals(1, result.posted)
        // The re-offered id is acked too: a lost ack converges instead
        // of re-offering forever.
        assertEquals(listOf(listOf("cmd-p1", "cmd-p2")), deps.ackedBatches)
        assertTrue(result.acked)
    }

    @Test
    fun `prompt failure leaves the id unmarked and unacked`() {
        val posted = MemPosted()
        val deps = FakeDeps(
            AppInstallCommandPoll.FetchOutcome.Success(parsed("bad", "good")),
            failPromptFor = setOf("bad"),
        )
        val result = runPoll(deps, posted)

        assertEquals(listOf("good"), deps.prompted)
        assertFalse(posted.isPosted("cmd-bad"))
        assertTrue(posted.isPosted("cmd-good"))
        // Only the prompted id is acked; the failed one stays offered
        // server-side for the next poll.
        assertEquals(listOf(listOf("cmd-good")), deps.ackedBatches)
        assertEquals(1, result.posted)
        assertTrue(result.acked)
    }

    @Test
    fun `ack failure keeps posted marks and retries next poll`() {
        val posted = MemPosted()
        val failing = FakeDeps(
            AppInstallCommandPoll.FetchOutcome.Success(parsed("p1")),
            ackResult = false,
        )
        val first = runPoll(failing, posted)
        assertEquals(listOf("p1"), failing.prompted)
        assertTrue(posted.isPosted("cmd-p1"))
        assertFalse(first.acked)

        // Next poll: the server re-offers; no duplicate prompt, ack retried.
        val retry = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1")))
        val second = runBlocking {
            AppInstallCommandPoll.pollOnceWith(retry, posted) { retry.logs.add(it) }
        }
        assertTrue(retry.prompted.isEmpty())
        assertEquals(1, second.skipped)
        assertEquals(listOf(listOf("cmd-p1")), retry.ackedBatches)
        assertTrue(second.acked)
    }

    @Test
    fun `unknown kinds are acked and ignored never prompted`() {
        // The server stops re-offering an unknown entry once its command
        // id is acked; the app must never post a notification for it.
        // Unknown entries without any command id cannot be addressed and
        // stay unacked (the server-side TTL still collects them).
        val shapes = AppInstallCommands.ParsedCommands(
            valid = listOf(payload("p1")),
            unknownKinds = 2,
            unknownIds = listOf("cmd-unknown-1"),
            malformed = 0,
            syntaxError = false,
        )
        val deps = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(shapes))
        val result = runPoll(deps)

        assertEquals(listOf("p1"), deps.prompted)
        assertEquals(listOf(listOf("cmd-p1", "cmd-unknown-1")), deps.ackedBatches)
        assertTrue(result.acked)
    }

    @Test
    fun `http and transport failures never post or ack`() {
        for (outcome in listOf(
            AppInstallCommandPoll.FetchOutcome.HttpError(401),
            AppInstallCommandPoll.FetchOutcome.HttpError(500),
            AppInstallCommandPoll.FetchOutcome.TransportFailure,
        )) {
            val deps = FakeDeps(outcome)
            val posted = MemPosted()
            val result = runPoll(deps, posted)
            assertTrue(deps.prompted.isEmpty())
            assertTrue(deps.ackedBatches.isEmpty())
            assertEquals(0, result.offered)
            assertTrue(result.acked)
        }
    }

    @Test
    fun `fetch throwing is a silent transport failure`() {
        val deps = object : AppInstallCommandPoll.Collaborators {
            override suspend fun fetch(): AppInstallCommandPoll.FetchOutcome =
                throw IOException("down")

            override suspend fun postPrompt(payload: AppInstallPayload) {
                fail("no post")
            }

            override suspend fun ack(ids: List<String>): Boolean {
                fail("no ack")
                throw AssertionError("no ack")
            }
        }
        val result = runBlocking {
            AppInstallCommandPoll.pollOnceWith(deps, MemPosted()) { }
        }
        assertEquals(0, result.offered)
    }

    @Test
    fun `cancellation is rethrown from fetch prompt and ack`() {
        val ceFetch = object : AppInstallCommandPoll.Collaborators {
            override suspend fun fetch(): AppInstallCommandPoll.FetchOutcome =
                throw CancellationException()

            override suspend fun postPrompt(payload: AppInstallPayload) = Unit
            override suspend fun ack(ids: List<String>): Boolean = true
        }
        try {
            runBlocking { AppInstallCommandPoll.pollOnceWith(ceFetch, MemPosted()) { } }
            fail("fetch CE must propagate")
        } catch (_: CancellationException) {
        }

        val cePrompt = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1")))
        val cePromptDeps = object : AppInstallCommandPoll.Collaborators by cePrompt {
            override suspend fun postPrompt(payload: AppInstallPayload): Unit =
                throw CancellationException()
        }
        try {
            runBlocking { AppInstallCommandPoll.pollOnceWith(cePromptDeps, MemPosted()) { } }
            fail("prompt CE must propagate")
        } catch (_: CancellationException) {
        }

        val ceAck = object : AppInstallCommandPoll.Collaborators {
            override suspend fun fetch() =
                AppInstallCommandPoll.FetchOutcome.Success(parsed("p1"))

            override suspend fun postPrompt(payload: AppInstallPayload) = Unit
            override suspend fun ack(ids: List<String>): Boolean = throw CancellationException()
        }
        try {
            runBlocking { AppInstallCommandPoll.pollOnceWith(ceAck, MemPosted()) { } }
            fail("ack CE must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `counts flow through to the result`() {
        val withShapes = AppInstallCommands.ParsedCommands(
            valid = listOf(payload("p1")),
            unknownKinds = 3,
            malformed = 2,
            syntaxError = false,
        )
        val deps = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(withShapes))
        val result = runPoll(deps)
        assertEquals(3, result.unknownKinds)
        assertEquals(2, result.malformed)
        assertEquals(1, result.posted)
    }

    @Test
    fun `collaborators are exactly fetch post and ack`() {
        // No widget, cache, or Glance edge exists on this path: the
        // interface the poll core can reach is fetch/postPrompt/ack only.
        val names = AppInstallCommandPoll.Collaborators::class.java.declaredMethods
            .map { it.name }.toSet()
        assertEquals(setOf("fetch", "postPrompt", "ack"), names)
    }
}
