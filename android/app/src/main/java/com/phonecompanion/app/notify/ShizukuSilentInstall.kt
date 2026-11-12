package com.phonecompanion.app.notify

/**
 * Shizuku silent-install gateway: classification + verification decision.
 *
 * Pure JVM (zero Android imports): the live Shizuku statics stay behind
 * [Probe] (implemented on the Android side), so the status model, the
 * ask-once affordance rule, and the install-verified decision are
 * unit-testable without a device.
 *
 * Mechanism (Android side, `ShizukuPrivilegedInstall`): the `package`
 * system-service binder fetched through Shizuku is wrapped so calls run
 * with shell privilege; a `PackageInstaller` built on that binder creates
 * a MODE_FULL_INSTALL session (replace-existing semantics) for the
 * verified staged APK, writes the APK bytes into the session from our
 * own storage, and commits. The commit carries a status receiver whose
 * broadcast is only ever observed — a user-confirmation intent is never
 * launched on this path, ever. Shell `pm` is deliberately NOT used: it
 * is fragile across ROMs (user targeting, output parsing, file
 * visibility), where the privileged session path is the proven one.
 *
 * Verification (never trust the commit call alone): after commit the
 * installed package's versionCode (read back via PackageManager) must
 * equal the staged APK's versionCode (parsed once before commit and
 * passed through). Mismatch or absent after the grace window is a
 * failure of the silent attempt — the caller falls back to the existing
 * chain (dialog PackageInstaller path → ACTION_VIEW → terminal), exactly
 * as for transport trouble. Unknown installer states fail closed to the
 * dialog path, never silent-retry loops.
 *
 * Multi-user: the privileged installer targets the current user only.
 * Work-profile installs are future work, not implemented here.
 *
 * Status model:
 * - [Status.UNAVAILABLE] — Shizuku pre-v11 (unsupported) or no live
 *   binder. Every install stays on the dialog path, unchanged.
 * - [Status.AVAILABLE_DENIED] — binder alive, permission not granted. A
 *   foreground UI may offer the one-time enable (see
 *   [shouldOfferRequest]); background receipts stay on the dialog path.
 * - [Status.AVAILABLE_GRANTED] — binder alive + permission granted: a
 *   verified APK may commit silently via the privileged session.
 *
 * Permission-request discipline (Shizuku semantics, which are INVERTED
 * from AOSP runtime permissions): `shouldShowRationale == true` means the
 * user picked "Deny and don't ask again". So the enable affordance shows
 * only while rationale is false — after any denial it disappears and the
 * dialog flow proceeds silently (ask once, never nag).
 *
 * Logging discipline: package names only — never APK paths, URLs, hashes,
 * tokens, or installer output.
 */
object ShizukuSilentInstall {

    /** Silent-install availability for one install attempt. */
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
     * implement the install half; `ShizukuProbe` implements the
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

    /** Outcome of one privileged-session commit attempt. */
    sealed interface PrivilegedOutcome {
        /**
         * The session committed (or the commit call returned). [status]
         * is the installer's status int when one arrived in time, null
         * when no status was received (unknown — the read-back decides).
         * Never terminal on its own: the caller must still verify via
         * [isVerifiedInstall].
         */
        data class Committed(val status: Int?) : PrivilegedOutcome

        /**
         * Transport failure (no binder, bind/permission loss, commit
         * threw, session could not be written): NEVER a terminal failure
         * — the caller falls back to the dialog flow from the cached
         * verified APK.
         */
        data object TransportDied : PrivilegedOutcome
    }

    /**
     * Budget for one privileged session status to arrive (slow devices
     * finalize large sessions late). Expiry is NOT a failure — the
     * read-back still decides; it only stops waiting for the broadcast.
     */
    const val COMMIT_STATUS_TIMEOUT_MS = 120_000L

    /**
     * Grace window for the post-commit read-back: the installed
     * versionCode must equal the expected one within this long, else the
     * silent attempt fails over to the dialog chain.
     */
    const val VERIFY_GRACE_MS = 30_000L

    /** Poll interval inside the verify grace window. */
    const val VERIFY_POLL_MS = 500L

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
     * Staged-path sanity: absolute, an APK file, no control characters.
     * The flow always stages `<dir>/<pushId>.apk`, so anything else is a
     * protocol violation — the privileged commit refuses it before any
     * session is created.
     */
    fun isStagedApkPath(path: String): Boolean {
        if (path.isEmpty() || !path.startsWith("/") || !path.endsWith(".apk")) return false
        for (c in path) {
            if (c.isISOControl() || c == '\n' || c == '\r') return false
        }
        return true
    }

    /**
     * Post-commit verification decision: the install counts as silently
     * installed ONLY when the staged APK's versionCode (parsed once
     * before commit) equals the currently installed versionCode (read
     * back after commit). Null on either side — unparseable staged APK,
     * package absent, PackageManager unreadable — is a mismatch (fail
     * closed to the dialog path), never a silent success. Never throws.
     */
    fun isVerifiedInstall(expectedVersionCode: Long?, installedVersionCode: Long?): Boolean =
        expectedVersionCode != null && installedVersionCode != null &&
            expectedVersionCode == installedVersionCode
}
