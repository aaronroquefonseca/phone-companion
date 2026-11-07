package com.phonecompanion.app.notify

/**
 * Remote APK install push ("app_install") wire payload.
 *
 * Frozen wire contract (see task brief): the app receives an FCM data
 * message with string fields `type=app_install`, `push_id`, `apk_url`
 * (https only), `sha256` (64 lowercase hex), `package` (e.g.
 * com.example.app), `version` (optional), `label` (required,
 * human-readable).
 *
 * Pure Kotlin, zero Android imports: JVM-testable without an emulator,
 * Firebase, or credentials. Parsing never throws — invalid payloads
 * (missing/oversized/invalid fields) yield null (rejected, no crash) and
 * the caller falls back to the generic [NotificationPayload] rendering so
 * unknown/invalid kinds keep rendering generically.
 */
data class AppInstallPayload(
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
     * Stable notification id so re-deliveries of the same push replace
     * rather than stack. Namespaced so it never collides with the generic
     * [NotificationPayload.notifyId] space in practice.
     */
    fun notifyId(): Int = ("app_install:$pushId").hashCode()

    /**
     * Cache file name for the downloaded APK. Sanitized: [pushId] is
     * operator-influenced and may contain path separators, so only
     * alphanumerics, `-`, `_` survive (anything else becomes `_`) — no
     * traversal out of the app cache dir is possible. Capped at 64 chars
     * plus prefix/suffix so the name stays filesystem-safe.
     */
    fun cacheFileName(): String = cacheFileNameFor(pushId)

    companion object {
        /** FCM data discriminator for this flow (frozen contract). */
        const val TYPE = "app_install"

        /** Hard cap on downloaded APK bytes (100 MB, frozen contract). */
        const val MAX_APK_BYTES: Long = 100L * 1024L * 1024L

        /** Intent/Bundle keys carrying a validated push across components. */
        const val EXTRA_PUSH_ID = "pc_app_push_id"
        const val EXTRA_APK_URL = "pc_app_apk_url"
        const val EXTRA_SHA256 = "pc_app_sha256"
        const val EXTRA_PACKAGE = "pc_app_package"
        const val EXTRA_VERSION = "pc_app_version"
        const val EXTRA_LABEL = "pc_app_label"

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
        const val MAX_URL_CHARS = 2048
        const val MAX_PACKAGE_CHARS = 255
        const val MAX_LABEL_CHARS = 200
        const val MAX_VERSION_CHARS = 64

        private const val SHORT_SHA_CHARS = 12
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        /**
         * True when this FCM data map is an app-install push. Accepts the
         * frozen `type` discriminator and, for tolerance, the legacy `kind`
         * spelling — anything else (including absent) is a generic
         * notification, never an install.
         */
        fun isAppInstall(data: Map<String, String>): Boolean =
            data["type"]?.trim() == TYPE || data["kind"]?.trim() == TYPE

        /**
         * Parses and validates [data]. Returns null when any required field
         * is missing, oversized, or invalid (bad URL scheme, bad sha256
         * shape, bad package shape, blank label/push_id). Never throws.
         */
        fun fromMap(data: Map<String, String>): AppInstallPayload? = try {
            parse(data)
        } catch (_: Exception) {
            null
        }

        private fun parse(data: Map<String, String>): AppInstallPayload? {
            val pushId = data["push_id"]?.trim().orEmpty()
            if (pushId.isEmpty() || pushId.length > MAX_PUSH_ID_CHARS) return null

            val apkUrl = data["apk_url"]?.trim().orEmpty()
            if (!isValidApkUrl(apkUrl)) return null

            val sha256 = data["sha256"]?.trim().orEmpty()
            if (!SHA256_REGEX.matches(sha256)) return null

            val packageName = data["package"]?.trim().orEmpty()
            if (packageName.isEmpty() || packageName.length > MAX_PACKAGE_CHARS) return null
            if (!PACKAGE_REGEX.matches(packageName)) return null

            val label = data["label"]?.trim().orEmpty()
            if (label.isEmpty() || label.length > MAX_LABEL_CHARS) return null

            val version = data["version"]?.trim()?.takeIf { it.isNotEmpty() }
            if (version != null && version.length > MAX_VERSION_CHARS) return null

            return AppInstallPayload(
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
         * string map). Nulls are dropped; the result is re-validated, so a
         * tampered or truncated extras bundle is rejected, never trusted.
         */
        fun fromExtras(extras: Map<String, String?>): AppInstallPayload? {
            val data = HashMap<String, String>(extras.size)
            for ((k, v) in extras) {
                if (v != null) data[k] = v
            }
            return fromMap(data)
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
