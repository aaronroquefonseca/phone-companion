package com.phonecompanion.app.notify

/**
 * Remote APK install push ("app_install") wire payload.
 *
 * Frozen wire contract (see the task brief / docs): the app polls
 * `GET /api/v1/outbox` and each `app_install` command entry carries
 * string fields `type=app_install`, `command_id` (per-device id used for
 * dedupe and ack), `push_id`, `apk_url` (https only),
 * `sha256` (64 lowercase hex), `package` (e.g. com.example.app),
 * `version` (optional), `label` (required, human-readable).
 *
 * Pure Kotlin, zero Android imports: JVM-testable without an emulator,
 * Firebase, or credentials. Parsing never throws — invalid payloads
 * (missing/oversized/invalid fields) yield null (rejected, no crash) and
 * the caller skips the entry with shape-only logging, so unknown or
 * malformed command entries never break the poll.
 */
data class AppInstallPayload(
    val commandId: String,
    val pushId: String,
    val apkUrl: String,
    val sha256: String,
    val packageName: String,
    val version: String?,
    val label: String,
) {
    /** Display version: the pushed version or 'unknown version'. */
    fun displayVersion(): String = version?.takeIf { it.isNotBlank() } ?: "unknown version"

    /** Short SHA-256 for the pre-install UI: first 12 chars + '…'. */
    fun shortSha(): String = sha256.take(SHORT_SHA_CHARS) + "…"

    /**
     * Single key owner for outbound intents: every component writes
     * extras through this map (never hand-rolled putExtra chains that
     * can drift from [fromExtras]). No attempt nonce here — the
     * attempt is created per download start (auto or explicit tap).
     */
    fun toExtras(): Map<String, String> {
        val out = HashMap<String, String>(7)
        out[EXTRA_COMMAND_ID] = commandId
        out[EXTRA_PUSH_ID] = pushId
        out[EXTRA_APK_URL] = apkUrl
        out[EXTRA_SHA256] = sha256
        out[EXTRA_PACKAGE] = packageName
        if (!version.isNullOrEmpty()) out[EXTRA_VERSION] = version
        out[EXTRA_LABEL] = label
        return out
    }

    /**
     * Stable notification id so re-deliveries of the same push replace
     * rather than stack. Namespaced so it never collides with the generic
     * [NotificationPayload.notifyId] space in practice.
     */
    fun notifyId(): Int = notifyIdFor(pushId)

    /**
     * Cache file name for the downloaded APK. Sanitized: [pushId] is
     * operator-influenced and may contain path separators, so only
     * alphanumerics, `-`, `_` survive (anything else becomes `_`) — no
     * traversal out of the app cache dir is possible. Capped at 64 chars
     * plus prefix/suffix so the name stays filesystem-safe.
     */
    fun cacheFileName(): String = cacheFileNameFor(pushId)

    companion object {
        /** Command discriminator for this flow (frozen contract). */
        const val TYPE = "app_install"

        /** Hard cap on downloaded APK bytes (100 MB, frozen contract). */
        const val MAX_APK_BYTES: Long = 100L * 1024L * 1024L

        /** Intent/Bundle keys carrying a validated push across components. */
        const val EXTRA_COMMAND_ID = "pc_app_command_id"
        const val EXTRA_PUSH_ID = "pc_app_push_id"
        const val EXTRA_APK_URL = "pc_app_apk_url"
        const val EXTRA_SHA256 = "pc_app_sha256"
        const val EXTRA_PACKAGE = "pc_app_package"
        const val EXTRA_VERSION = "pc_app_version"
        const val EXTRA_LABEL = "pc_app_label"

        /**
         * Per-Install-tap attempt nonce. Created by [AppInstallSessions]
         * when a download starts (auto on receipt, or the activity's
         * Install tap) and carried through the progress Cancel intent and
         * the installer result intent into every terminal claim, so
         * exactly-once is scoped per attempt — never per push id per
         * minute. Absent on the detail tap (no attempt exists yet);
         * receivers tolerate it missing (legacy single key).
         */
        const val EXTRA_ATTEMPT = "pc_app_attempt"

        /**
         * Traversal-safe cache file name for [pushId] (see
         * [AppInstallPayload.cacheFileName]). Single owner so every
         * component computes the identical path from a push id string.
         */
        fun cacheFileNameFor(pushId: String): String {
            val safe = pushId.map { c ->
                if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
            }.joinToString("").take(64).ifEmpty { "push" }
            return "app-push-$safe.apk"
        }

        const val MAX_PUSH_ID_CHARS = 128
        const val MAX_COMMAND_ID_CHARS = 128
        const val MAX_URL_CHARS = 2048
        const val MAX_PACKAGE_CHARS = 255
        const val MAX_LABEL_CHARS = 200
        const val MAX_VERSION_CHARS = 64

        private const val SHORT_SHA_CHARS = 12
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val MULTI_SPACE_REGEX = Regex(" {2,}")

        /**
         * Bidi/format controls stripped from consent-screen text (see
         * [sanitizeSingleLine]). Explicit set — not all of Cf — so ZWJ
         * emoji sequences keep rendering.
         */
        private val BIDI_FORMAT_STRIP: Set<Char> = buildSet {
            add('\u200E'); add('\u200F') // LRM/RLM
            for (c in '\u202A'..'\u202E') add(c) // embeddings/overrides
            for (c in '\u2060'..'\u2064') add(c) // word joiner etc.
            for (c in '\u2066'..'\u2069') add(c) // isolates
            add('\u061C') // arabic letter mark
            add('\uFEFF') // zero-width no-break space
            add('\u180E') // mongolian vowel separator
        }

        /**
         * True when a polled command entry is an app-install command.
         * Accepts the frozen `type` discriminator and, for tolerance,
         * the legacy `kind` spelling — anything else (including absent)
         * is an unknown kind, ignored by the poll (never an install).
         */
        fun isAppInstall(data: Map<String, String>): Boolean =
            data["type"]?.trim() == TYPE || data["kind"]?.trim() == TYPE

        /**
         * Parses and validates [data]. Returns null when any required field
         * is missing, oversized, or invalid (bad URL scheme, bad sha256
         * shape, bad package shape, blank label/push_id/command_id). Never
         * throws.
         */
        fun fromMap(data: Map<String, String>): AppInstallPayload? = try {
            parse(data)
        } catch (_: Exception) {
            null
        }

        private fun parse(data: Map<String, String>): AppInstallPayload? {
            // Per-device command id: the only value the server accepts in
            // the ack body. An entry without one can never be acked, so it
            // must not be prompted either (else the server re-offers it
            // forever and the pending cap fills).
            val commandId = data["command_id"]?.trim().orEmpty()
            if (commandId.isEmpty() || commandId.length > MAX_COMMAND_ID_CHARS) return null

            val pushId = data["push_id"]?.trim().orEmpty()
            if (pushId.isEmpty() || pushId.length > MAX_PUSH_ID_CHARS) return null

            val apkUrl = data["apk_url"]?.trim().orEmpty()
            if (!isValidApkUrl(apkUrl)) return null

            val sha256 = data["sha256"]?.trim().orEmpty()
            if (!SHA256_REGEX.matches(sha256)) return null

            val packageName = data["package"]?.trim().orEmpty()
            if (packageName.isEmpty() || packageName.length > MAX_PACKAGE_CHARS) return null
            if (!PACKAGE_REGEX.matches(packageName)) return null

            // Consent-screen hardening: label/version render on the
            // pre-install screen, so newlines, carriage returns and
            // bidi/format controls are sanitized to a single line here —
            // a hostile operator must not be able to spoof or re-order
            // the consent text. The consent screen shows
            // package/version/short-SHA (kept verbatim: validated shapes).
            val label = sanitizeSingleLine(data["label"]?.trim().orEmpty())
            if (label.isEmpty() || label.length > MAX_LABEL_CHARS) return null

            val version = data["version"]?.trim()?.let(::sanitizeSingleLine)?.takeIf { it.isNotEmpty() }
            if (version != null && version.length > MAX_VERSION_CHARS) return null

            return AppInstallPayload(
                commandId = commandId,
                pushId = pushId,
                apkUrl = apkUrl,
                sha256 = sha256,
                packageName = packageName,
                version = version,
                label = label,
            )
        }

        /**
         * Rebuilds a validated payload from Intent extras (or any nullable
         * string map). Accepts BOTH spellings: the [EXTRA_*] intent keys
         * written by [toExtras] (single key owner) and the wire keys
         * (`push_id`, `apk_url`, …) — intent first, wire fallback — then
         * re-validates, so a tampered or truncated extras bundle is
         * rejected, never trusted.
         */
        fun fromExtras(extras: Map<String, String?>): AppInstallPayload? {
            val wire = HashMap<String, String>(extras.size)
            fun take(wireKey: String, intentKey: String, wireSpelling: String) {
                val v = extras[intentKey] ?: extras[wireSpelling]
                if (!v.isNullOrEmpty()) wire[wireKey] = v
            }
            take("command_id", EXTRA_COMMAND_ID, "command_id")
            take("push_id", EXTRA_PUSH_ID, "push_id")
            take("apk_url", EXTRA_APK_URL, "apk_url")
            take("sha256", EXTRA_SHA256, "sha256")
            take("package", EXTRA_PACKAGE, "package")
            take("version", EXTRA_VERSION, "version")
            take("label", EXTRA_LABEL, "label")
            return fromMap(wire)
        }

        /** Attempt nonce from prompt/activity extras, if one was attached. */
        fun attemptFromExtras(extras: Map<String, String?>): String? =
            extras[EXTRA_ATTEMPT]?.takeIf { it.isNotBlank() }
                ?: extras["attempt"]?.takeIf { it.isNotBlank() }

        /** Stable notification id for [pushId]: single owner (see [notifyId]). */
        fun notifyIdFor(pushId: String): Int = ("app_install:$pushId").hashCode()

        /**
         * Single-line sanitizer for consent-screen text (label/version):
         * CR/LF/TAB fold to one space, other C0/C1 controls and the
         * bidi/format controls (U+200E/F, U+202A–E, U+2060–69, U+061C,
         * U+FEFF, U+180E) are dropped. ZWJ and other Cf outside this set
         * are kept so emoji sequences still render.
         */
        internal fun sanitizeSingleLine(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (c in raw) {
                when {
                    c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
                    c.isISOControl() -> { /* drop other C0/C1 */ }
                    c in BIDI_FORMAT_STRIP -> { /* drop spoof-capable controls */ }
                    else -> sb.append(c)
                }
            }
            return sb.toString().trim().replace(MULTI_SPACE_REGEX, " ")
        }

        /** HTTPS-only, host required; anything else fails closed to false. */
        fun isValidApkUrl(raw: String): Boolean {
            if (raw.isEmpty() || raw.length > MAX_URL_CHARS) return false
            if (!raw.regionMatches(0, "https://", 0, "https://".length, ignoreCase = true)) {
                return false
            }
            if (raw.substring("https://".length).substringBefore("/").isEmpty()) return false
            return true
        }
    }
}
