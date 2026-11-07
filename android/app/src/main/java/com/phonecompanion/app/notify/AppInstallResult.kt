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
 * Every outcome deletes the cached APK and posts exactly one
 * user-visible notification; nothing retries, ever.
 */
object AppInstallResult {

    // Mirrors of android.content.pm.PackageInstaller status constants.
    const val STATUS_PENDING = -1
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
    }

    /**
     * Maps a PackageInstaller status int to its terminal [Outcome].
     * Unknown/pending codes fail closed to [Outcome.Failed]. Never throws.
     */
    fun classify(status: Int, statusMessage: String?): Outcome = when (status) {
        STATUS_SUCCESS -> Outcome.Installed
        // The system reports an explicit user dismiss/abort under this
        // code; it is informational, never an error and never retried.
        STATUS_FAILURE_ABORTED -> Outcome.UserCancelled
        else -> Outcome.Failed(statusMessage)
    }
}
