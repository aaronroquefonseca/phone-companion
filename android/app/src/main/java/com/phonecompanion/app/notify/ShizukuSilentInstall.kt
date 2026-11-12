package com.phonecompanion.app.notify

/**
 * Shizuku silent-install gateway: classification + shell-result parsing.
 *
 * Pure JVM (zero Android imports): the live Shizuku statics live behind
 * [Probe] (implemented by `ShizukuProbe` on the Android side), so the
 * status model, the ask-once affordance rule, and the result packing are
 * unit-testable without a device.
 *
 * Status model:
 * - [Status.UNAVAILABLE] — Shizuku pre-v11 (unsupported) or no live
 *   binder. Every install stays on the dialog path, unchanged.
 * - [Status.AVAILABLE_DENIED] — binder alive, permission not granted. A
 *   foreground UI may offer the one-time enable (see
 *   [shouldOfferRequest]); background receipts stay on the dialog path.
 * - [Status.AVAILABLE_GRANTED] — binder alive + permission granted: a
 *   verified APK may commit silently via the shell UserService.
 *
 * Permission-request discipline (Shizuku semantics, which are INVERTED
 * from AOSP runtime permissions): `shouldShowRationale == true` means the
 * user picked "Deny and don't ask again". So the enable affordance shows
 * only while rationale is false — after any denial it disappears and the
 * dialog flow proceeds silently (ask once, never nag).
 *
 * Logging discipline: package names only — never APK paths, URLs, hashes,
 * tokens, or shell output.
 */
object ShizukuSilentInstall {

    /** Shell-install availability for one install attempt. */
    enum class Status {
        /** Pre-v11 or binder dead: dialog path, unchanged. */
        UNAVAILABLE,

        /** Binder alive, permission missing: foreground may offer enable. */
        AVAILABLE_DENIED,

        /** Binder alive + granted: verified APKs commit silently. */
        AVAILABLE_GRANTED,
    }

    /**
     * Android edge of the gateway (Shizuku statics). The flow hosts
     * implement the install half; [ShizukuProbe] implements the
     * classification half against the live binder.
     */
    interface Probe {
        /** False on Shizuku pre-v11 (unsupported — dialog path). */
        fun isSupported(): Boolean

        /** True while the Shizuku binder is alive. */
        fun isBinderAlive(): Boolean

        /** True once the user granted this app Shizuku permission. */
        fun isPermissionGranted(): Boolean

        /**
         * Shizuku semantics: true means "Deny and don't ask again" —
         * never prompt. False means first ask (or still askable).
         */
        fun shouldShowRationale(): Boolean
    }

    /** Outcome of one shell `pm install` attempt. */
    sealed interface ShellOutcome {
        /** pm exited (0 = installed); [output] is capped display detail. */
        data class Finished(val exitCode: Int, val output: String) : ShellOutcome

        /**
         * Transport failure (binder died mid-flow, bind timeout, staging
         * failure): NEVER a terminal failure — the caller falls back to
         * the dialog flow from the cached verified APK.
         */
        data object BinderDied : ShellOutcome
    }

    /** Packed shell-result cap: pm output is short; bound it anyway. */
    const val MAX_SHELL_OUTPUT_CHARS = 4096

    /**
     * Classifies one attempt. Order matters: an unsupported server or a
     * dead binder is UNAVAILABLE even if a stale grant flag lingers.
     */
    fun statusOf(probe: Probe): Status {
        val supported = try {
            probe.isSupported()
        } catch (_: Exception) {
            false
        }
        if (!supported) return Status.UNAVAILABLE
        val alive = try {
            probe.isBinderAlive()
        } catch (_: Exception) {
            false
        }
        if (!alive) return Status.UNAVAILABLE
        val granted = try {
            probe.isPermissionGranted()
        } catch (_: Exception) {
            false
        }
        return if (granted) Status.AVAILABLE_GRANTED else Status.AVAILABLE_DENIED
    }

    /**
     * True when the pre-install UI should show the enable affordance:
     * binder alive, permission missing, and not in don't-ask-again.
     * After any denial this returns false (ask once, never nag) and the
     * dialog flow proceeds silently.
     */
    fun shouldOfferRequest(probe: Probe): Boolean {
        if (statusOf(probe) != Status.AVAILABLE_DENIED) return false
        return try {
            !probe.shouldShowRationale()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Staged-path sanity: absolute, an APK file, no control characters
     * that could smuggle extra pm arguments. The flow always stages
     * `<dir>/<pushId>.apk`, so anything else is a protocol violation —
     * the service rejects it before spawning.
     */
    fun isStagedApkPath(path: String): Boolean {
        if (path.isEmpty() || !path.startsWith("/") || !path.endsWith(".apk")) return false
        for (c in path) {
            if (c.isISOControl() || c == '\n' || c == '\r') return false
        }
        return true
    }

    /**
     * Parses the UserService result (`"exit:<code>\n<output>"`). Never
     * throws: a malformed pack is a Finished failure (transient,
     * retryable), never a transport fallback and never a crash. Output is
     * capped ([MAX_SHELL_OUTPUT_CHARS]) before it rides into notification
     * extras.
     */
    fun parseShellResult(raw: String?): ShellOutcome.Finished {
        if (raw.isNullOrEmpty()) return ShellOutcome.Finished(-1, "")
        val nl = raw.indexOf('\n')
        val first = if (nl < 0) raw else raw.substring(0, nl)
        val rest = if (nl < 0) "" else raw.substring(nl + 1)
        val code = first.removePrefix("exit:").toIntOrNull()
            ?: return ShellOutcome.Finished(-1, raw.take(MAX_SHELL_OUTPUT_CHARS))
        return ShellOutcome.Finished(code, rest.take(MAX_SHELL_OUTPUT_CHARS))
    }
}
