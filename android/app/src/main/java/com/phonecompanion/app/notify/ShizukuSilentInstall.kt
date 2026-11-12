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
     * Fallback for the hidden `PackageManager.INSTALL_FROM_ADB` flag
     * (AOSP value `0x00000020`) when the field is absent on some API
     * level. The privileged session carries this flag so the system
     * trusts it like an `adb install` and skips the user-confirmation
     * dialog for the shell caller.
     */
    const val INSTALL_FROM_ADB_FALLBACK = 0x00000020

    /**
     * Resolves the hidden `PackageManager.INSTALL_FROM_ADB` install
     * flag reflectively from [packageManagerClass] (the hidden field is
     * not in the compile-SDK surface). Falls back to
     * [INSTALL_FROM_ADB_FALLBACK] when the field is absent — never
     * throws. Pure ([Class]-in) so it is unit-testable without a
     * device.
     */
    fun resolveInstallFromAdbFlag(packageManagerClass: Class<*>): Int {
        return try {
            packageManagerClass.getField("INSTALL_FROM_ADB").getInt(null)
        } catch (_: Exception) {
            INSTALL_FROM_ADB_FALLBACK
        }
    }

    /**
     * Fallback for `PackageManager.INSTALL_REPLACE_EXISTING`
     * (AOSP value `0x00000002`). A shell-created session does NOT get
     * this flag for free (PackageManagerService adds it only for app
     * callers), and every real push in this product is an update — so
     * without it the commit fails with INSTALL_FAILED_ALREADY_EXISTS
     * even once the session itself is privileged.
     */
    const val INSTALL_REPLACE_EXISTING_FALLBACK = 0x00000002

    /** What [applyInstallFlags] actually set on one `SessionParams`. */
    data class InstallFlagsApplied(
        val applied: Boolean = false,
        val installFromAdb: Boolean = false,
        val replaceExisting: Boolean = false,
    )

    /**
     * ORs INSTALL_FROM_ADB and INSTALL_REPLACE_EXISTING into a
     * `SessionParams` through its PUBLIC `installFlags` field. The field
     * (not a get/setInstallFlags method pair — those live on `SessionInfo`
     * and `Session`, never on `SessionParams`) is the only real surface on
     * API 26+. Reflectively read+written under the caller's HiddenApiBypass
     * exemptions. Never throws: a missing field returns
     * [InstallFlagsApplied] with `applied=false` and the caller proceeds
     * exactly as before (dialog fallback + read-back verification decide).
     * Takes [sessionParams] as [Any] so it is unit-testable without a device.
     */
    fun applyInstallFlags(sessionParams: Any): InstallFlagsApplied {
        return try {
            val field = sessionParams.javaClass.getField("installFlags")
            val current = field.getInt(sessionParams)
            field.setInt(
                sessionParams,
                current or INSTALL_FROM_ADB_FALLBACK or INSTALL_REPLACE_EXISTING_FALLBACK,
            )
            InstallFlagsApplied(applied = true, installFromAdb = true, replaceExisting = true)
        } catch (_: Exception) {
            InstallFlagsApplied()
        }
    }

    /**
     * Fallback for the hidden `PackageManager.INSTALL_REASON_USER`
     * install reason (AOSP value `4`) when the field is absent on some
     * API level. The privileged session reports a user-initiated install
     * so PackageManagerService treats it like an `adb install`, which is
     * never user-confirmed.
     */
    const val INSTALL_REASON_USER_FALLBACK = 4

    /**
     * Fallback for the hidden `USER_ACTION_NOT_REQUIRED` session flag
     * (AOSP value `2`) when the field is absent on some API level.
     * Explicitly marks the privileged session as needing no user action —
     * the ackpine-proven way to suppress the system confirmation dialog.
     */
    const val USER_ACTION_NOT_REQUIRED_FALLBACK = 2

    /**
     * Per-attribute result of [applySessionTrust]: which ackpine-proven
     * session attributes were actually applied. The caller decides the
     * outcome from this (dialog fallback + read-back verification), never
     * from assumed behavior.
     */
    data class SessionTrustApplied(
        val originatingUid: Boolean = false,
        val installReason: Boolean = false,
        val userAction: Boolean = false,
    )

    /**
     * Resolves the hidden `PackageManager.INSTALL_REASON_USER` install
     * reason reflectively from [packageManagerClass], falling back to
     * [INSTALL_REASON_USER_FALLBACK] when the field is absent. Never
     * throws. Pure ([Class]-in) so it is unit-testable without a device.
     */
    fun resolveInstallReasonUser(packageManagerClass: Class<*>): Int {
        return try {
            packageManagerClass.getField("INSTALL_REASON_USER").getInt(null)
        } catch (_: Exception) {
            INSTALL_REASON_USER_FALLBACK
        }
    }

    /**
     * Resolves the hidden `USER_ACTION_NOT_REQUIRED` session flag
     * reflectively from [sessionParamsClass], falling back to
     * [USER_ACTION_NOT_REQUIRED_FALLBACK] when the field is absent. Never
     * throws. Pure ([Class]-in) so it is unit-testable without a device.
     */
    fun resolveUserActionNotRequired(sessionParamsClass: Class<*>): Int {
        return try {
            sessionParamsClass.getField("USER_ACTION_NOT_REQUIRED").getInt(null)
        } catch (_: Exception) {
            USER_ACTION_NOT_REQUIRED_FALLBACK
        }
    }

    /**
     * Applies the ackpine-proven silent session shape to [sessionParams]:
     * originating UID = the Shizuku peer (shell), user-initiated install
     * reason, and no user action required. Without these,
     * PackageManagerService treats the privileged session as an ordinary
     * app install and posts the system confirmation dialog. Each attribute
     * is applied reflectively under the caller's HiddenApiBypass
     * exemptions and fails open individually: absent methods leave that
     * attribute unset, and an absent Shizuku UID ([shizukuUid] null, e.g.
     * binder died mid-read) skips the originating UID — the dialog
     * fallback + read-back verification decide the outcome either way.
     * API gates mirror the platform: originating UID needs 24+,
     * install reason 26+, no-user-action 31+. Never throws. Takes
     * [sessionParams] as [Any] so it is unit-testable without a device.
     */
    fun applySessionTrust(
        sessionParams: Any,
        shizukuUid: Int?,
        sdkInt: Int,
        installReasonUser: Int,
        userActionNotRequired: Int,
    ): SessionTrustApplied {
        var originatingUid = false
        var installReason = false
        var userAction = false
        if (shizukuUid != null && sdkInt >= 24) {
            originatingUid = invokeIntSetter(sessionParams, "setOriginatingUid", shizukuUid)
        }
        if (sdkInt >= 26) {
            installReason = invokeIntSetter(sessionParams, "setInstallReason", installReasonUser)
        }
        if (sdkInt >= 31) {
            userAction = invokeIntSetter(sessionParams, "setRequireUserAction", userActionNotRequired)
        }
        return SessionTrustApplied(originatingUid, installReason, userAction)
    }

    private fun invokeIntSetter(sessionParams: Any, name: String, value: Int): Boolean {
        return try {
            sessionParams.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(sessionParams, value)
            true
        } catch (_: Exception) {
            false
        }
    }

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
