package com.phonecompanion.app.ui

/**
 * Persists the POST_NOTIFICATIONS denial across process death (finding B3).
 *
 * The old in-memory/savedInstanceState flag reset when the process died, so
 * a hard-denied user was shown the first-ask rationale again instead of the
 * persistent Settings line. This tracker reads the persisted value on
 * construction and writes it on every permission result; MainActivity wires
 * it to SharedPreferences, tests wire it to an in-memory map.
 */
class NotificationDenialTracker(
    readPersisted: () -> Boolean,
    private val writePersisted: (Boolean) -> Unit,
) {
    var deniedBefore: Boolean = readPersisted()
        private set

    fun onPermissionResult(granted: Boolean) {
        deniedBefore = !granted
        writePersisted(!granted)
    }
}
