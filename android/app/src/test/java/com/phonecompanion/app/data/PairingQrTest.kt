package com.phonecompanion.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrTest {

    @Test
    fun `canonical JSON parses base url and token`() {
        val result = parsePairingQr("""{"base_url":"https://example.com","enroll_token":"abc123"}""")
        assertEquals(QrParse.Ok(PairingQr("https://example.com", "abc123")), result)
    }

    @Test
    fun `JSON aliases are accepted`() {
        val result = parsePairingQr("""{"server_url":"https://example.com/","token":"tok"}""")
        assertEquals(QrParse.Ok(PairingQr("https://example.com/", "tok")), result)
    }

    @Test
    fun `JSON without token is invalid`() {
        val result = parsePairingQr("""{"base_url":"https://example.com"}""")
        assertTrue(result is QrParse.Invalid)
    }

    @Test
    fun `bare token falls back to manual base url`() {
        val result = parsePairingQr("  sometoken123  ")
        assertEquals(QrParse.Ok(PairingQr("", "sometoken123")), result)
    }

    @Test
    fun `deep link query token parses`() {
        val result = parsePairingQr("https://example.com/pair?enroll_token=tok123")
        val ok = result as QrParse.Ok
        assertEquals("tok123", ok.payload.enrollToken)
    }

    @Test
    fun `backend P2 enroll_url payload derives base url`() {
        val result = parsePairingQr(
            """{"enroll_url":"https://example.com/api/v1/enroll","enroll_token":"tok"}""",
        )
        assertEquals(QrParse.Ok(PairingQr("https://example.com", "tok")), result)
    }

    @Test
    fun `plain base_url still wins over enroll_url`() {
        val result = parsePairingQr(
            """{"base_url":"https://example.com","enroll_url":"https://other.example/api/v1/enroll","enroll_token":"tok"}""",
        )
        assertEquals(QrParse.Ok(PairingQr("https://example.com", "tok")), result)
    }

    @Test
    fun `empty input is invalid`() {
        assertTrue(parsePairingQr(null) is QrParse.Invalid)
        assertTrue(parsePairingQr("   ") is QrParse.Invalid)
    }
}
