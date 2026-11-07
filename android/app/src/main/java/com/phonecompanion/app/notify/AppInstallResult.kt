package com.phonecompanion.app.notify

/**
 * PackageInstaller result classification.
 *
 * Pure JVM (plain ints, zero Android imports): android.content.pm
 * constants are inlined as documented values so the mapping is
 * unit-testable without Robolectric. The Android receiver
 * ([AppInstallResultReceiver]) passes through
 * `PackageInstaller.EXTRA_STATUS` verbatim.
 *
 * Every terminal outcome deletes the cached APK and posts exactly one
 * user-visible notification; nothing retries, ever. The one non-terminal
 * outcome ([Outcome.PendingUserAction]) launches the system's
 * confirmation intent and stays silent — the cached APK is kept for the
 * follow-up result.
 *
 * Install-phase failures are cause-aware ([failureKindOf]): a
 * signature/update-incompatible failure is deterministic (retrying the
 * same bytes can never succeed), so its notification offers the target
 * app's system App-info screen instead of a retry; any other
 * commit/install failure is treated as transient and offers a retry that
 * resumes from the cached verified APK. The raw installer message is
 * kept as secondary detail only — never the primary explanation.
 */
object AppInstallResult {

    // Mirrors of android.content.pm.PackageInstaller status constants.
    // STATUS_PENDING_USER_ACTION (-1) is the system asking the user to
    // confirm (EXTRA_INTENT carries the confirmation activity): the
    // receiver launches it — never a terminal outcome, never a delete.
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_ABORTED = 2
    const val STATUS_FAILURE_BLOCKED = 3
    const val STATUS_FAILURE_CONFLICT = 4
    const val STATUS_FAILURE_INCOMPATIBLE = 5
    const val STATUS_FAILURE_INVALID = 6
    const val STATUS_FAILURE_STORAGE = 7

    /** Terminal outcome of an install session. */
    sealed interface Outcome {
        /** Installer committed: success notification, cache cleaned. */
        data object Installed : Outcome

        /**
         * User cancelled (dismissed the system dialog or aborted):
         * plain informational notification, NO retry, NO retry loop.
         */
        data object UserCancelled : Outcome

        /** Installer reported failure: error notification with its message. */
        data class Failed(val statusMessage: String?) : Outcome

        /**
         * System needs explicit user confirmation first: not terminal —
         * the receiver launches the confirmation intent and stays silent
         * (the follow-up result re-enters [classify]).
         */
        data object PendingUserAction : Outcome
    }

    /**
     * Maps a PackageInstaller status int to its [Outcome]. Unknown codes
     * fail closed to [Outcome.Failed]. Never throws.
     */
    fun classify(status: Int, statusMessage: String?): Outcome = when (status) {
        STATUS_SUCCESS -> Outcome.Installed
        // The system reports an explicit user dismiss/abort under this
        // code; it is informational, never an error and never retried.
        STATUS_FAILURE_ABORTED -> Outcome.UserCancelled
        STATUS_PENDING_USER_ACTION -> Outcome.PendingUserAction
        else -> Outcome.Failed(statusMessage)
    }

    /**
     * Cause-aware kind of an install/commit failure. Drives the failure
     * notification's copy and its action button ([failureActions]).
     */
    enum class FailureKind {
        /**
         * The update was built with a different signing key than the
         * installed build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` /
         * "signatures do not match"). Deterministic: retrying the same
         * bytes can never succeed — the user must uninstall the old app
         * first, then install fresh. Never offers Retry.
         */
        SIGNATURE_MISMATCH,

        /**
         * Any other commit/install failure (session abort, storage/IO at
         * commit, installer unavailable, unknown message). Treated as
         * transient: offers Retry, which resumes from the cached verified
         * APK without re-downloading when it is still intact.
         */
        TRANSIENT,
    }

    /** Notification action button offered on a failure notification. */
    enum class FailureAction {
        /** Re-opens the pre-install UI; its Install tap resumes the flow. */
        RETRY,

        /** Opens the update target's system App-info screen (Uninstall). */
        APP_INFO,
    }

    /**
     * Maps an installer status message to its [FailureKind]. Status-code
     * agnostic on purpose: the incompatibility arrives as a generic
     * `STATUS_FAILURE` with the `INSTALL_FAILED_*` reason in the message.
     * Null/blank/unknown messages fail open to [FailureKind.TRANSIENT]
     * (a retry is harmless; a missing App-info button would strand). Case
     * insensitive. Never throws.
     */
    fun failureKindOf(statusMessage: String?): FailureKind {
        val message = statusMessage.orEmpty()
        return if (message.contains("update_incompatible", ignoreCase = true) ||
            message.contains("signatures do not match", ignoreCase = true)
        ) {
            FailureKind.SIGNATURE_MISMATCH
        } else {
            FailureKind.TRANSIENT
        }
    }

    /**
     * Action buttons for a [FailureKind]: signature mismatch offers
     * App-info only (retrying is futile); transient failures offer Retry
     * only. User-cancelled, hash-mismatch, blocked, and too-large paths
     * never reach here and stay action-less. Never throws.
     */
    fun failureActions(kind: FailureKind): Set<FailureAction> = when (kind) {
        FailureKind.SIGNATURE_MISMATCH -> setOf(FailureAction.APP_INFO)
        FailureKind.TRANSIENT -> setOf(FailureAction.RETRY)
    }
}
