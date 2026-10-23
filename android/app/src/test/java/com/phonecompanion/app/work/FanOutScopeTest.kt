package com.phonecompanion.app.work

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the structured-concurrency contract WidgetPoller.updateAllWidgets
 * relies on: [supervisorScope] isolates sibling failures while still
 * propagating parent cancellation — unlike a bare SupervisorJob(), which
 * detaches children from the parent scope so they outlive a dead worker.
 */
class FanOutScopeTest {

    @Test
    fun `sibling failure does not cancel siblings`() = runBlocking {
        val completed = mutableListOf<String>()
        supervisorScope {
            listOf(
                async {
                    try {
                        throw IllegalStateException("one type broke")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        completed += "failed-type-contained"
                    }
                },
                async { completed += "sibling-ok" },
            ).awaitAll()
        }
        assertEquals(setOf("failed-type-contained", "sibling-ok"), completed.toSet())
    }

    @Test
    fun `parent cancellation reaches children`() = runBlocking {
        var childStarted = false
        var childSawCancellation = false
        val parent = launch {
            supervisorScope {
                launch {
                    try {
                        childStarted = true
                        delay(Long.MAX_VALUE)
                    } catch (e: CancellationException) {
                        childSawCancellation = true
                        throw e
                    }
                }
                delay(Long.MAX_VALUE)
            }
        }
        while (!childStarted) delay(1)
        parent.cancelAndJoin()
        assertTrue(childSawCancellation)
    }

    @Test
    fun `poller fans out under supervisorScope without detached SupervisorJobs`() {
        val candidates = listOf(
            "src/main/java/com/phonecompanion/app/work/WidgetPoller.kt",
            "app/src/main/java/com/phonecompanion/app/work/WidgetPoller.kt",
            "android/app/src/main/java/com/phonecompanion/app/work/WidgetPoller.kt",
        )
        val source = candidates.map(::File).firstOrNull { it.isFile }
        assertTrue("WidgetPoller.kt not found from ${System.getProperty("user.dir")}", source != null)
        val text = source!!.readText()
        assertTrue(text.contains("supervisorScope"))
        assertFalse(
            "bare SupervisorJob() detaches children from the parent scope",
            text.contains("SupervisorJob()"),
        )
    }
}
