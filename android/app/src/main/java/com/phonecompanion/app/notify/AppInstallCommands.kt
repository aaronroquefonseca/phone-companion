package com.phonecompanion.app.notify

import com.phonecompanion.app.data.AppMoshi
import com.squareup.moshi.Types

/**
 * Poll-triggered command parsing (`GET /api/v1/commands`).
 *
 * Same lenient-unknown-kinds / strict-known-fields discipline as the
 * notification parser ([NotificationPayload]): entries whose `type` is
 * not `app_install` are ignored (counted, never crash — the backend can
 * add install-adjacent kinds later without an app update), and
 * `app_install` entries with missing/oversized/invalid fields are
 * skipped. Validation itself is owned by [AppInstallPayload.fromMap]
 * (single key owner, both spellings, round-trip tested) — this file only
 * splits one response body into (valid, unknown, malformed) counts.
 *
 * Pure Kotlin + Moshi (zero Android imports): JVM-testable without an
 * emulator. Parsing never throws for shape problems (they are counts);
 * only JSON-syntax failures surface as [ParsedCommands.syntaxError] so
 * the caller can log the body byte length (shape only, never content).
 * Nothing here logs entry contents — counts only.
 */
object AppInstallCommands {

    /** One parsed `GET /api/v1/commands` body. */
    data class ParsedCommands(
        /** Validated `app_install` payloads, in response order. */
        val valid: List<AppInstallPayload>,
        /** Entries with a non-`app_install` (or absent) discriminator. */
        val unknownKinds: Int,
        /** `app_install` entries failing [AppInstallPayload] validation. */
        val malformed: Int,
        /** True when the body was not decodable JSON at all. */
        val syntaxError: Boolean,
    )

    private val envelopeType =
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)

    private val envelopeAdapter by lazy {
        AppMoshi.moshi.adapter<Map<String, Any?>>(envelopeType)
    }

    /**
     * Splits [raw] into valid/unknown/malformed counts. Never throws:
     * undecodable bodies yield [ParsedCommands.syntaxError] and every
     * non-object entry counts as malformed.
     */
    fun parseResponse(raw: String): ParsedCommands {
        if (raw.isBlank()) return ParsedCommands(emptyList(), 0, 0, syntaxError = false)
        val envelope = try {
            envelopeAdapter.fromJson(raw)
        } catch (_: Exception) {
            return ParsedCommands(emptyList(), 0, 0, syntaxError = true)
        } ?: return ParsedCommands(emptyList(), 0, 0, syntaxError = true)
        val entries = envelope["commands"] as? List<*> ?: return ParsedCommands(
            emptyList(), 0, 0, syntaxError = true,
        )
        val valid = ArrayList<AppInstallPayload>(entries.size)
        var unknown = 0
        var malformed = 0
        for (entry in entries) {
            val map = if (entry is Map<*, *>) stringify(entry) else null
            if (map == null) {
                malformed++
                continue
            }
            if (!AppInstallPayload.isAppInstall(map)) {
                unknown++
                continue
            }
            val payload = AppInstallPayload.fromMap(map)
            if (payload == null) {
                malformed++
            } else {
                valid.add(payload)
            }
        }
        return ParsedCommands(valid, unknown, malformed, syntaxError = false)
    }

    /**
     * Keeps string-valued entries verbatim; drops nulls and non-strings.
     * A numeric/boolean `push_id` therefore fails validation as missing
     * (strict known fields), while extra unknown keys vanish here
     * (lenient unknown fields).
     */
    private fun stringify(raw: Map<*, *>): Map<String, String> {
        val out = HashMap<String, String>(raw.size)
        for ((k, v) in raw) {
            if (k is String && v is String) out[k] = v
        }
        return out
    }
}
