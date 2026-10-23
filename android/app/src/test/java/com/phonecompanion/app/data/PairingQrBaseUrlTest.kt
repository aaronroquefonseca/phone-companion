package com.phonecompanion.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fix batch B: `baseFromEnrollUrl` robustness. The QR `enroll_url` may
 * carry a trailing slash (`.../enroll/`) or a query/fragment
 * (`.../enroll?enroll_token=...`); those must be stripped BEFORE the
 * endpoint-suffix strip, otherwise the base keeps a dangling `/enroll`.
 * Exercised through the public [parsePairingQr] entrypoint.
 */
class PairingQrBaseUrlTest {

    private fun baseFor(enrollUrl: String): String {
        val result = parsePairingQr(
            """{"enroll_url":"$enrollUrl","enroll_token":"tok"}""",
        ) as QrParse.Ok
        return result.payload.baseUrl
    }

    @Test
    fun `plain enroll suffix strips to base`() {
        assertEquals("https://example.com", baseFor("https://example.com/api/v1/enroll"))
    }

    @Test
    fun `trailing slash enroll form strips to base`() {
        assertEquals("https://example.com", baseFor("https://example.com/api/v1/enroll/"))
    }

    @Test
    fun `enroll url with query strips to base`() {
        assertEquals(
            "https://example.com",
            baseFor("https://example.com/api/v1/enroll?enroll_token=tok&foo=bar"),
        )
    }

    @Test
    fun `enroll url with fragment strips to base`() {
        assertEquals(
            "https://example.com",
            baseFor("https://example.com/api/v1/enroll#section"),
        )
    }

    @Test
    fun `enroll slash with query and fragment strips to base`() {
        assertEquals(
            "https://example.com",
            baseFor("https://example.com/api/v1/enroll/?enroll_token=tok#section"),
        )
    }

    @Test
    fun `subpath installs keep their prefix`() {
        assertEquals(
            "https://example.com/sub",
            baseFor("https://example.com/sub/api/v1/enroll/?x=1#f"),
        )
    }

    @Test
    fun `bare api version suffix still strips`() {
        assertEquals("https://example.com", baseFor("https://example.com/api/v1"))
    }
}
