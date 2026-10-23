package com.phonecompanion.app.ui

/**
 * Notification-permission prompt policy (finding 1).
 *
 * The runtime permission is requested only once this phone is paired — an
 * unpaired launcher screen never prompts — either right after a successful
 * pairing (the just-paired confirmation offers it) or when the first
 * notification render path needs it. Before the system dialog, the app shows
 * its own one-line rationale; after a denial it shows a persistent line with
 * a Settings deep-link instead of re-prompting.
 *
 * Pure decision table, JVM-unit-testable; MainActivity feeds it the platform
 * state and renders the result.
 */
object NotificationPermissionPolicy {

    /** What the paired UI should show about notifications. */
    enum class PromptState {
        /** Permission already granted (or OS < 33): nothing to show. */
        GRANTED,
        /** Unpaired, or permission not applicable: never prompt. */
        HIDDEN,
        /** First ask: show the pre-rationale line + Allow button. */
        RATIONALE,
        /** Denied (don't-ask-again or previously declined): persistent line + Settings link. */
        DENIED,
    }

    /**
     * @param paired whether credentials exist (never prompt when unpaired).
     * @param granted whether POST_NOTIFICATIONS is currently granted
     * (or the OS predates the runtime permission).
     * @param deniedBefore whether a previous request was denied.
     * @param shouldShowRationale platform hint that an educational UI is
     * appropriate (a repeat ask, not a hard "don't ask again").
     */
    fun decide(
        paired: Boolean,
        granted: Boolean,
        deniedBefore: Boolean,
        shouldShowRationale: Boolean,
    ): PromptState {
        if (!paired || granted) return if (granted) PromptState.GRANTED else PromptState.HIDDEN
        if (!deniedBefore) return PromptState.RATIONALE
        return if (shouldShowRationale) PromptState.RATIONALE else PromptState.DENIED
    }
}
