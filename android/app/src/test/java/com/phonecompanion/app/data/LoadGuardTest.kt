package com.phonecompanion.app.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadGuardTest {

    @Test
    fun `block result passes through`() = runBlocking {
        assertEquals(42, loadOrFallback(-1) { 42 })
    }

    @Test
    fun `exceptions fall back`() = runBlocking {
        assertEquals("fallback", loadOrFallback("fallback") { throw IOException("disk gone") })
        assertEquals("fallback", loadOrFallback("fallback") { throw IllegalStateException("bad row") })
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown, never swallowed`() {
        runBlocking {
            loadOrFallback("fallback") { throw CancellationException("cancelled") }
        }
    }
}
