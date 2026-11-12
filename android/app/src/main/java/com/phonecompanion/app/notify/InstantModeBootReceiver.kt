package com.phonecompanion.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts instant mode after a reboot or after the app itself is updated.
 *
 * Every app update kills the process, which stops the foreground service
 * even though the user's preference stays "on". Without this receiver the
 * user had to open the app again after each self-update — exactly the
 * manual step instant mode exists to remove. Both broadcasts are
 * best-effort: a refused foreground start just leaves the next app open
 * to restart it.
 */
class InstantModeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!InstantMode.isEnabled(context)) return
        try {
            InstantModeService.start(context)
        } catch (e: Exception) {
            Log.i(TAG, "restart refused [${e.javaClass.simpleName}]")
        }
    }

    private companion object {
        const val TAG = "InstantModeBoot"
    }
}
