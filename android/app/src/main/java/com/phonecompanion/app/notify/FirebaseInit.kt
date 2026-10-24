package com.phonecompanion.app.notify

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.phonecompanion.app.BuildConfig

/**
 * Runtime Firebase initialization (option (b): no google-services Gradle
 * plugin — applying it would hard-fail every build without the gitignored
 * google-services.json, breaking CI, fresh checkouts, and the F-Droid path
 * the README promises stay green).
 *
 * Sender config reaches the APK via BuildConfig fields, populated at build
 * time from the operator's local `android/app/google-services.json` when
 * present and `TODO-OPERATOR` placeholders otherwise (see app/build.gradle).
 * [ensure] is idempotent — guarded by `FirebaseApp.getApps()`, so calling it
 * on every enroll/token-rotation is a no-op once initialized — and fails
 * closed: without config (or on init error) it logs [LOG_INIT_FAILED] and
 * returns false, and callers proceed without push. The token-fetch failure
 * downstream (Play services unreachable) logs the distinct
 * [LOG_PLAY_UNREACHABLE], so the two causes are distinguishable in logcat.
 * Neither line carries secret material (no keys, tokens, or config values —
 * exception class names only).
 */
object FirebaseInit {
    const val TAG = "FirebaseInit"

    /** Placeholder baked into BuildConfig when google-services.json is absent. */
    const val PLACEHOLDER = "TODO-OPERATOR"

    /** Logged when FirebaseApp cannot be initialized (missing/misconfigured sender config). */
    const val LOG_INIT_FAILED =
        "firebase init failed: FirebaseApp not configured, enroll proceeds without push"

    /** Logged when FirebaseApp exists but no token can be fetched (Play services unreachable). */
    const val LOG_PLAY_UNREACHABLE =
        "fcm token fetch failed: Play services unreachable, enroll proceeds without push"

    /**
     * Pure decision, JVM-testable: true only when all four sender fields
     * are present (no blanks, no [PLACEHOLDER]).
     */
    fun isConfigured(senderId: String, projectId: String, appId: String, apiKey: String): Boolean =
        listOf(senderId, projectId, appId, apiKey).none { it.isBlank() || it == PLACEHOLDER }

    /** BuildConfig-backed check for production call sites. */
    fun isConfigured(): Boolean = isConfigured(
        BuildConfig.FCM_SENDER_ID,
        BuildConfig.FCM_PROJECT_ID,
        BuildConfig.FCM_APP_ID,
        BuildConfig.FCM_API_KEY,
    )

    /**
     * Ensures the default [FirebaseApp] exists, initializing it once from
     * BuildConfig sender fields when needed. Safe to call from every
     * enroll and token-rotation entry point. Never throws; false means
     * "proceed without push".
     */
    fun ensure(context: Context): Boolean = ensure(
        alreadyInitialized = { FirebaseApp.getApps(context).isNotEmpty() },
        doInit = {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .build(),
            )
        },
    )

    /**
     * Injectable-seam overload: production passes FirebaseApp calls, unit
     * tests pass fakes (android.util.Log and FirebaseApp need a device).
     */
    internal fun ensure(
        alreadyInitialized: () -> Boolean,
        doInit: () -> Unit,
        configured: Boolean = isConfigured(),
        log: (String) -> Unit = { msg -> Log.w(TAG, msg) },
    ): Boolean {
        // Idempotency guard: an initialized app is never re-initialized.
        if (alreadyInitialized()) return true
        if (!configured) {
            log(LOG_INIT_FAILED)
            return false
        }
        return try {
            doInit()
            // Verify rather than assume: only report success when the
            // default app is actually registered now. A silent false here
            // would hide a broken init, so it logs the init-failed line.
            if (alreadyInitialized()) {
                true
            } else {
                log(LOG_INIT_FAILED)
                false
            }
        } catch (e: Exception) {
            // Class name only — exception messages are never logged, so no
            // config value can leak into logcat here.
            log("$LOG_INIT_FAILED [${e.javaClass.simpleName}]")
            false
        }
    }
}
