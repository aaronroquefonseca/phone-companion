package com.phonecompanion.app.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption affordance for the pre-install screen.
 *
 * WHY: even expedited WorkManager work can sit deferred minutes-to-hours
 * in Samsung's deep standby buckets, and the inline wake fetch only helps
 * while an FCM wake arrives — Doze/App-Standby still throttles everything
 * else (polls, transfers). An unrestricted bucket keeps the whole path
 * prompt. The row is informational only: it names the current state and,
 * when restricted, offers one Fix button that opens the system's own
 * exemption screen. No nagging (no dialogs, no auto-prompting, no repeat
 * asks), no behavior change beyond the row.
 *
 * Structure mirrors [ShizukuSilentInstall]: the row-visibility decision
 * ([statusOf]/[shouldOfferFix]) is pure boolean logic and JVM-tested; the
 * Android edges ([isExempt]/[requestIntent]) are thin and never throw.
 */
object BatteryExemption {

    /** Row states: exempt (unrestricted bucket) or not. */
    enum class Status {
        UNRESTRICTED,
        RESTRICTED,
    }

    /**
     * Pure visibility mapping: granted exemption → unrestricted row with
     * no Fix button; anything else → restricted row with the Fix button.
     */
    fun statusOf(isExempt: Boolean): Status =
        if (isExempt) Status.UNRESTRICTED else Status.RESTRICTED

    /**
     * True when the pre-install row should show its Fix button (restricted
     * only — an exempt app has nothing to fix, so the button hides rather
     * than nags).
     */
    fun shouldOfferFix(isExempt: Boolean): Boolean = !isExempt

    /**
     * Android edge: true when the app is already ignoring battery
     * optimizations (unrestricted bucket). Fail-closed to false — a
     * failed query just shows the restricted row, which is harmless.
     * Never throws.
     */
    fun isExempt(appContext: Context): Boolean {
        return try {
            val pm = appContext.applicationContext
                .getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return false
            pm.isIgnoringBatteryOptimizations(appContext.applicationContext.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * System exemption-request intent for this package. Fired with a plain
     * `startActivity` — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * carries no result contract, so it consumes NO request code
     * (verified against this screen's only request code, the Shizuku
     * listener code — a different namespace entirely). The caller
     * re-renders on resume when the user returns from Settings.
     */
    fun requestIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
}
