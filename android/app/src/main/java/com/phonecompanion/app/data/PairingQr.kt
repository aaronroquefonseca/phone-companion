package com.phonecompanion.app.data

/**
 * Pairing-QR payload parsing. Pure Kotlin (no Android APIs) so it is
 * unit-testable on the JVM.
 *
 * Accepted QR contents, in order of preference:
 * 1. JSON object with the single-use enroll token plus the server address,
 *    e.g. `{"enroll_url":"https://example.com/api/v1/enroll",
 *    "enroll_token":"..."}` (backend P2 canonical form) or equivalently
 *    `{"base_url":"https://example.com","enroll_token":"..."}`.
 *    Key aliases (`base_url`, `server_url`, `baseUrl`, `url`,
 *    `enrollToken`, `token`) are accepted so the WebUI pairing page has
 *    room to evolve without an app update.
 * 2. A enroll deep-link/query URL carrying `enroll_token=...` (base URL must
 *    then come from the manual server-URL field).
 * 3. The bare enroll token (same fallback as 2).
 *
 * This is the app side of a convention the backend pairing page must also
 * implement; JSON form (1) is the canonical one to generate.
 */
data class PairingQr(val baseUrl: String, val enrollToken: String)

sealed interface QrParse {
    data class Ok(val payload: PairingQr) : QrParse
    data class Invalid(val reason: String) : QrParse
}

fun parsePairingQr(raw: String?): QrParse {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return QrParse.Invalid("empty QR code")

    if (text.startsWith("{")) {
        val token = extractJsonField(text, "enroll_token", "enrollToken", "token")
        if (token.isNullOrBlank()) {
            return QrParse.Invalid("QR JSON has no enroll token")
        }
        // Plain base URL wins; otherwise derive it from the backend P2
        // canonical `enroll_url` by stripping the endpoint suffix.
        val base = extractJsonField(text, "base_url", "server_url", "baseUrl", "url")
            ?: extractJsonField(text, "enroll_url")?.let(::baseFromEnrollUrl)
        return QrParse.Ok(PairingQr(base.orEmpty(), token))
    }

    val queryToken = Regex("[?#&]enroll_token=([^&#\\s]+)").find(text)?.groupValues?.get(1)
    if (queryToken != null) {
        if (queryToken.isBlank()) return QrParse.Invalid("QR link has an empty enroll token")
        return QrParse.Ok(PairingQr("", queryToken))
    }

    // Bare token; server URL comes from the manual field.
    if (text.length > 4096 || text.contains("\\s".toRegex())) {
        return QrParse.Invalid("unrecognized QR content")
    }
    return QrParse.Ok(PairingQr("", text))
}

private fun extractJsonField(json: String, vararg names: String): String? {
    for (name in names) {
        val match = Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)
        if (match != null) return match.groupValues[1]
    }
    return null
}

/** Strips the `/api/v1/enroll` endpoint suffix to recover the server base. */
private fun baseFromEnrollUrl(enrollUrl: String): String =
    enrollUrl.trim()
        .removeSuffix("/api/v1/enroll")
        .removeSuffix("/api/v1")
        .removeSuffix("/")
