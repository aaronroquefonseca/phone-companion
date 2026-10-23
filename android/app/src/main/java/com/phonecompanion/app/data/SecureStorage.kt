@file:Suppress("DEPRECATION") // See class kdoc: 1.1.x deprecation without stable replacement.

package com.phonecompanion.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted store for this device's credentials.
 *
 * Holds exactly: the server base URL, the server-issued `device_id`, the
 * raw `device_token` (Bearer), the base64 per-device `device_key`, and the
 * latest FCM registration token (pending upload / next enroll).
 *
 * Everything secret-bearing lives in [EncryptedSharedPreferences]
 * (AES-256-SIV keys, AES-256-GCM values, Tink-backed MasterKey); nothing
 * here is ever logged. Widget payload caching is the widgets PR's job and
 * lives elsewhere — this store is credentials only.
 */
// 1.1.x marks EncryptedSharedPreferences deprecated without shipping a stable
// DataStore replacement; it remains the documented credentials approach and
// functions as before, so the usage is suppressed at file level by design.
class SecureStorage private constructor(context: Context) : EnrollmentStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = putOrRemove(KEY_BASE_URL, value)

    /** Opaque server-issued id; doubles as the v1 encryption `kid`. */
    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        private set(value) = putOrRemove(KEY_DEVICE_ID, value)

    /** Raw Bearer token, shown once by the server. Never log. */
    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        private set(value) = putOrRemove(KEY_DEVICE_TOKEN, value)

    /** Base64 256-bit per-device key. Never log, never transmit. */
    var deviceKey: String?
        get() = prefs.getString(KEY_DEVICE_KEY, null)
        private set(value) = putOrRemove(KEY_DEVICE_KEY, value)

    /** Latest FCM registration token (may be null when push is unconfigured). */
    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = putOrRemove(KEY_FCM_TOKEN, value)

    /**
     * Human device name sent with the last successful enroll. Display-only:
     * shown on the paired line and the pair-success confirmation instead of
     * the raw device id. Null when never paired (or after [clearCredentials]).
     */
    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        private set(value) = putOrRemove(KEY_DEVICE_NAME, value)

    val isPaired: Boolean
        get() = !deviceId.isNullOrBlank() && !deviceToken.isNullOrBlank() &&
            !deviceKey.isNullOrBlank() && !baseUrl.isNullOrBlank()

    /**
     * Persists a fresh enrollment, overwriting any previous credentials.
     * Re-pair rotates the per-device key by design (see api-contract.md):
     * payloads encrypted under the old key become undecryptable.
     * Synchronous commit: credentials must be durable before the caller
     * proceeds (e.g. process death right after a 201).
     *
     * @return the `commit()` result: false means the enrollment did NOT
     * persist and the caller must not report success (see
     * [EnrollRepository.Result.Unexpected] `"persist-failed"`).
     */
    override fun saveEnrollment(
        baseUrl: String,
        deviceId: String,
        deviceToken: String,
        deviceKey: String,
    ): Boolean {
        return prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_DEVICE_KEY, deviceKey)
            .commit()
    }

    override fun saveDeviceName(deviceName: String) {
        this.deviceName = deviceName.ifBlank { null }
    }

    /**
     * Forgets this device entirely (explicit unpair AND post-revoke reset
     * share this path): device credentials plus the server base URL and the
     * cached FCM token. Re-pair starts over from a fresh QR scan or manual
     * entry — no half-paired state is retained.
     *
     * Server-side revocation itself is the WebUI's job; this only drops the
     * local copy so a revoked phone fails closed to the unpaired state.
     */
    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_DEVICE_KEY)
            .remove(KEY_FCM_TOKEN)
            .remove(KEY_DEVICE_NAME)
            .apply()
    }

    private fun putOrRemove(key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrEmpty()) remove(key) else putString(key, value)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "pc_secure_creds"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_FCM_TOKEN = "fcm_token"

        @Volatile
        private var instance: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage =
            instance ?: synchronized(this) {
                instance ?: SecureStorage(context).also { instance = it }
            }
    }
}
