package com.phonecompanion.app.notify

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote-shell channel: outbox parsing and result-pack decoding. */
class ShellChannelTest {

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun `shell command parses with timeout clamp`() {
        val parsed = AppInstallCommands.parseResponse(
            """{"commands":[{"type":"shell","command_id":"c1","command":"id -u","timeout_ms":999999}]}""",
        )
        assertEquals(1, parsed.shell.size)
        assertEquals("c1", parsed.shell[0].commandId)
        assertEquals(120_000, parsed.shell[0].timeoutMs)
    }

    @Test
    fun `shell command without command id is malformed`() {
        val parsed = AppInstallCommands.parseResponse(
            """{"commands":[{"type":"shell","command":"id -u"}]}""",
        )
        assertTrue(parsed.shell.isEmpty())
        assertEquals(1, parsed.malformed)
    }

    @Test
    fun `result pack round-trips stdout and stderr`() {
        val raw = "0\n" + "0\n" + b64("hello\n") + "\n" + b64("warn")
        val outcome = ShizukuRemoteShell.parsePack(raw)
        assertEquals(0, outcome?.exitCode)
        assertEquals("hello\n", outcome?.stdout)
        assertEquals("warn", outcome?.stderr)
        assertFalse(outcome?.truncated == true)
    }

    @Test
    fun `result pack marks truncation`() {
        val raw = "124\n" + "1\n" + b64("x") + "\n" + b64("")
        val outcome = ShizukuRemoteShell.parsePack(raw)
        assertEquals(124, outcome?.exitCode)
        assertTrue(outcome?.truncated == true)
    }

    @Test
    fun `malformed result pack is rejected`() {
        assertNull(ShizukuRemoteShell.parsePack("not-a-pack"))
        assertNull(ShizukuRemoteShell.parsePack(""))
        assertNull(ShizukuRemoteShell.parsePack(null))
    }
}
