package com.phonecompanion.app.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation exemption for instant delivery.
 *
 * A high-priority FCM wake does start the app, but while the device is in
 * Doze (or Samsung's sleeping-apps state) the follow-up HTTP poll cannot
 * complete: the wake is delivered, yet background network is throttled.
 * The one-time "Unrestricted" battery exemption is Android's supported way
 * for a self-hosted companion app to keep that fetch working while the
 * phone is idle. The grant is entirely user-owned; the app only asks (from
 * a foreground menu action) and reads the state.
 */
object BatteryExemption {

    /** True when the OS already allows unrestricted background work. */
    fun isExempt(context: Context): Boolean = try {
        val pm = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.applicationContext.packageName)
    } catch (_: Exception) {
        // Unknown state must not nag: treat as exempt (no prompt shown).
        true
    }

    /**
     * The system's "allow background activity" request for this package.
     * Falls back to the app's details page when the direct request intent
     * is unavailable (some OEMs omit it).
     */
    fun requestIntent(context: Context): Intent {
        val app = context.applicationContext
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${app.packageName}"))
        return if (direct.resolveActivity(app.packageManager) != null) {
            direct
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${app.packageName}"))
        }
    }
}
