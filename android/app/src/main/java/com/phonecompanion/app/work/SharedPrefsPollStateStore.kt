package com.phonecompanion.app.work

import android.content.Context

/**
 * Plain-[SharedPreferences][android.content.SharedPreferences]-backed
 * [PollStateStore].
 *
 * Deliberately NOT [com.phonecompanion.app.data.SecureStorage]
 * (`EncryptedSharedPreferences`): the auth-failure streak and last poll
 * status are not secret, and the streak must remain readable/writable
 * across credential clears — wiping credentials must not (and does not)
 * reset poll state behind the poller's back. Only the wipe path itself
 * resets the streak (see [WidgetPoller]).
 */
class SharedPrefsPollStateStore(context: Context) : PollStateStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override var consecutiveAuthFailures: Int
        get() = prefs.getInt(KEY_CONSECUTIVE_AUTH_FAILURES, 0).coerceAtLeast(0)
        set(value) {
            prefs.edit().putInt(KEY_CONSECUTIVE_AUTH_FAILURES, value.coerceAtLeast(0)).apply()
        }

    override var lastStatus: PollStatus?
        get() {
            if (!prefs.contains(KEY_LAST_RESULT)) return null
            val result = prefs.getString(KEY_LAST_RESULT, null)?.let {
                try {
                    PollResult.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } ?: return null
            val code = prefs.getInt(KEY_LAST_HTTP_CODE, NO_CODE).takeIf { it != NO_CODE }
            return PollStatus(
                result = result,
                httpCode = code,
                timestampMs = prefs.getLong(KEY_LAST_TIMESTAMP_MS, 0L),
                consecutiveAuthFailures = prefs.getInt(KEY_LAST_CONSECUTIVE, 0).coerceAtLeast(0),
            )
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(KEY_LAST_RESULT)
                    .remove(KEY_LAST_HTTP_CODE)
                    .remove(KEY_LAST_TIMESTAMP_MS)
                    .remove(KEY_LAST_CONSECUTIVE)
            } else {
                edit.putString(KEY_LAST_RESULT, value.result.name)
                    .putInt(KEY_LAST_HTTP_CODE, value.httpCode ?: NO_CODE)
                    .putLong(KEY_LAST_TIMESTAMP_MS, value.timestampMs)
                    .putInt(KEY_LAST_CONSECUTIVE, value.consecutiveAuthFailures.coerceAtLeast(0))
            }
            edit.apply()
        }

    companion object {
        internal const val PREFS_NAME = "pc_poll_state"
        internal const val KEY_CONSECUTIVE_AUTH_FAILURES = "consecutive_auth_failures"
        internal const val KEY_LAST_RESULT = "last_result"
        internal const val KEY_LAST_HTTP_CODE = "last_http_code"
        internal const val KEY_LAST_TIMESTAMP_MS = "last_timestamp_ms"
        internal const val KEY_LAST_CONSECUTIVE = "last_consecutive"
        internal const val NO_CODE = -1
    }
}
