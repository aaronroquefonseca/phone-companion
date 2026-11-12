package com.phonecompanion.app.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Poll-triggered command orchestration (auto-download once + ack).
 *
 * Pins: fresh ids start their download then mark then ack;
 * already-posted re-offers skip the download but still ack (lost-ack
 * convergence); start failures are left unmarked AND unacked (server
 * re-offers next poll); ack failures keep the posted marks (no
 * re-download) and retry next poll; fetch failures never start/ack. The
 * collaborators surface is exactly fetch/startDownload/ack — no widget,
 * cache, or Glance edge exists for this path to touch. The wake type is
 * silent (never rendered): isWake separates it from install commands.
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
        var failStartFor: Set<String> = emptySet(),
    ) : AppInstallCommandPoll.Collaborators {
        val started = mutableListOf<String>()
        val ackedBatches = mutableListOf<List<String>>()
        val logs = mutableListOf<String>()

        override suspend fun fetch(): AppInstallCommandPoll.FetchOutcome = fetchOutcome

        override suspend fun startDownload(payload: AppInstallPayload) {
            if (payload.pushId in failStartFor) throw IOException("download down")
            started.add(payload.pushId)
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
    fun `fresh ids start mark and ack together`() = runBlocking {
        val deps = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1", "p2")))
        val posted = MemPosted()
        val result = AppInstallCommandPoll.pollOnceWith(deps, posted) { deps.logs.add(it) }

        assertEquals(listOf("p1", "p2"), deps.started)
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
    fun `already-posted re-offer skips the download but still acks`() {
        val posted = MemPosted()
        posted.markPosted(listOf("cmd-p1"))
        val deps = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1", "p2")))
        val result = runPoll(deps, posted)

        assertEquals(listOf("p2"), deps.started)
        assertEquals(1, result.skipped)
        assertEquals(1, result.posted)
        // The re-offered id is acked too: a lost ack converges instead
        // of re-offering forever.
        assertEquals(listOf(listOf("cmd-p1", "cmd-p2")), deps.ackedBatches)
        assertTrue(result.acked)
    }

    @Test
    fun `start failure leaves the id unmarked and unacked`() {
        val posted = MemPosted()
        val deps = FakeDeps(
            AppInstallCommandPoll.FetchOutcome.Success(parsed("bad", "good")),
            failStartFor = setOf("bad"),
        )
        val result = runPoll(deps, posted)

        assertEquals(listOf("good"), deps.started)
        assertFalse(posted.isPosted("cmd-bad"))
        assertTrue(posted.isPosted("cmd-good"))
        // Only the started id is acked; the failed one stays offered
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
        assertEquals(listOf("p1"), failing.started)
        assertTrue(posted.isPosted("cmd-p1"))
        assertFalse(first.acked)

        // Next poll: the server re-offers; no duplicate download, ack retried.
        val retry = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1")))
        val second = runBlocking {
            AppInstallCommandPoll.pollOnceWith(retry, posted) { retry.logs.add(it) }
        }
        assertTrue(retry.started.isEmpty())
        assertEquals(1, second.skipped)
        assertEquals(listOf(listOf("cmd-p1")), retry.ackedBatches)
        assertTrue(second.acked)
    }

    @Test
    fun `unknown kinds are acked and ignored never started`() {
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

        assertEquals(listOf("p1"), deps.started)
        assertEquals(listOf(listOf("cmd-p1", "cmd-unknown-1")), deps.ackedBatches)
        assertTrue(result.acked)
    }

    @Test
    fun `http and transport failures never start or ack`() {
        for (outcome in listOf(
            AppInstallCommandPoll.FetchOutcome.HttpError(401),
            AppInstallCommandPoll.FetchOutcome.HttpError(500),
            AppInstallCommandPoll.FetchOutcome.TransportFailure,
        )) {
            val deps = FakeDeps(outcome)
            val posted = MemPosted()
            val result = runPoll(deps, posted)
            assertTrue(deps.started.isEmpty())
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

            override suspend fun startDownload(payload: AppInstallPayload) {
                fail("no start")
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
    fun `cancellation is rethrown from fetch start and ack`() {
        val ceFetch = object : AppInstallCommandPoll.Collaborators {
            override suspend fun fetch(): AppInstallCommandPoll.FetchOutcome =
                throw CancellationException()

            override suspend fun startDownload(payload: AppInstallPayload) = Unit
            override suspend fun ack(ids: List<String>): Boolean = true
        }
        try {
            runBlocking { AppInstallCommandPoll.pollOnceWith(ceFetch, MemPosted()) { } }
            fail("fetch CE must propagate")
        } catch (_: CancellationException) {
        }

        val ceStart = FakeDeps(AppInstallCommandPoll.FetchOutcome.Success(parsed("p1")))
        val ceStartDeps = object : AppInstallCommandPoll.Collaborators by ceStart {
            override suspend fun startDownload(payload: AppInstallPayload): Unit =
                throw CancellationException()
        }
        try {
            runBlocking { AppInstallCommandPoll.pollOnceWith(ceStartDeps, MemPosted()) { } }
            fail("start CE must propagate")
        } catch (_: CancellationException) {
        }

        val ceAck = object : AppInstallCommandPoll.Collaborators {
            override suspend fun fetch() =
                AppInstallCommandPoll.FetchOutcome.Success(parsed("p1"))

            override suspend fun startDownload(payload: AppInstallPayload) = Unit
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
    fun `collaborators are exactly fetch start shell and ack`() {
        // No widget, cache, or Glance edge exists on this path. Shell is
        // the operator remote-shell channel (execute + result upload).
        val names = AppInstallCommandPoll.Collaborators::class.java.declaredMethods
            .map { it.name }.toSet()
        assertEquals(setOf("fetch", "startDownload", "executeShell", "postShellResult", "ack"), names)
    }

    @Test
    fun `wake type is silent and distinct from install commands`() {
        // The server wake ({"type":"app_push_wake"}) must intercept before
        // any render path; install commands and unknown types fall through.
        assertTrue(AppInstallCommandPoll.isWake(mapOf("type" to "app_push_wake", "push_id" to "p1")))
        assertTrue(AppInstallCommandPoll.isWake(mapOf("type" to "  app_push_wake  ")))
        assertFalse(AppInstallCommandPoll.isWake(mapOf("type" to "app_install", "push_id" to "p1")))
        assertFalse(AppInstallCommandPoll.isWake(mapOf("kind" to "alert", "title" to "Hi")))
        assertFalse(AppInstallCommandPoll.isWake(emptyMap()))
        // A wake map is never a valid install payload (no command id), so
        // even a fallen-through wake could never start a download.
        assertNull(AppInstallPayload.fromMap(mapOf("type" to "app_push_wake", "push_id" to "p1")))
    }
}
