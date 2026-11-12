package com.phonecompanion.app.notify

import com.phonecompanion.app.data.AppMoshi
import com.squareup.moshi.Types

/**
 * Poll-triggered command parsing (`GET /api/v1/outbox`).
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

    /** One parsed `GET /api/v1/outbox` body. */
    data class ParsedCommands(
        /** Validated `app_install` payloads, in response order. */
        val valid: List<AppInstallPayload>,
        /** Entries with a non-`app_install` (or absent) discriminator. */
        val unknownKinds: Int,
        /**
         * Per-device command ids of unknown-kind entries that carried
         * one: the poll acks these (ignored, never prompted) so the
         * server stops re-offering them. Entries without any id cannot
         * be addressed and stay unacked.
         */
        val unknownIds: List<String> = emptyList(),
        /** `app_install` entries failing [AppInstallPayload] validation. */
        val malformed: Int,
        /** True when the body was not decodable JSON at all. */
        val syntaxError: Boolean,
        /** Validated `shell` commands (operator remote-shell channel). */
        val shell: List<ShellCommand> = emptyList(),
    )

    /**
     * One operator remote-shell command. Bounded like every other command:
     * a 4 KB command ceiling and a 1–120 s timeout, both re-validated on
     * the device so a malformed server row can never run unbounded.
     */
    data class ShellCommand(
        val commandId: String,
        val command: String,
        val timeoutMs: Int,
    )

    private const val MAX_SHELL_COMMAND_CHARS = 4096
    private const val MIN_SHELL_TIMEOUT_MS = 1_000
    private const val MAX_SHELL_TIMEOUT_MS = 120_000
    private const val DEFAULT_SHELL_TIMEOUT_MS = 30_000

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
        if (raw.isBlank()) return ParsedCommands(emptyList(), 0, emptyList(), 0, syntaxError = false)
        val envelope = try {
            envelopeAdapter.fromJson(raw)
        } catch (_: Exception) {
            return ParsedCommands(emptyList(), 0, emptyList(), 0, syntaxError = true)
        } ?: return ParsedCommands(emptyList(), 0, emptyList(), 0, syntaxError = true)
        val entries = envelope["commands"] as? List<*> ?: return ParsedCommands(
            emptyList(), 0, emptyList(), 0, syntaxError = true,
        )
        val valid = ArrayList<AppInstallPayload>(entries.size)
        val shell = ArrayList<ShellCommand>()
        var unknown = 0
        val unknownIds = ArrayList<String>()
        var malformed = 0
        for (entry in entries) {
            val map = if (entry is Map<*, *>) stringify(entry) else null
            if (map == null) {
                malformed++
                continue
            }
            if (map["type"]?.trim() == "shell") {
                val commandId = map["command_id"]?.trim().orEmpty()
                val command = map["command"]?.trim().orEmpty()
                // timeout_ms arrives as a JSON number (stringify keeps only
                // strings), so read it from the raw entry.
                val rawTimeout = (entry as Map<*, *>)["timeout_ms"]
                val timeout = when (rawTimeout) {
                    is Number -> rawTimeout.toInt()
                    is String -> rawTimeout.trim().toIntOrNull()
                    else -> null
                }?.coerceIn(MIN_SHELL_TIMEOUT_MS, MAX_SHELL_TIMEOUT_MS)
                    ?: DEFAULT_SHELL_TIMEOUT_MS
                if (commandId.isEmpty() || command.isEmpty() ||
                    command.length > MAX_SHELL_COMMAND_CHARS
                ) {
                    malformed++
                } else {
                    shell.add(ShellCommand(commandId, command, timeout))
                }
                continue
            }
            if (!AppInstallPayload.isAppInstall(map)) {
                unknown++
                // Ack-and-ignore: a command id lets the poll tell the
                // server this entry is resolved without prompting.
                map["command_id"]?.trim()?.takeIf { it.isNotEmpty() }?.let { unknownIds.add(it) }
                continue
            }
            val payload = AppInstallPayload.fromMap(map)
            if (payload == null) {
                malformed++
            } else {
                valid.add(payload)
            }
        }
        return ParsedCommands(valid, unknown, unknownIds, malformed, syntaxError = false, shell = shell)
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
