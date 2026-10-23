package com.phonecompanion.app.data

/**
 * Minimal credentials sink for enroll persistence.
 *
 * Lets [EnrollRepository] report a failed persist instead of claiming
 * success, and lets JVM unit tests fake storage ([SecureStorage] needs
 * `EncryptedSharedPreferences`, which needs an Android device).
 */
interface EnrollmentStore {

    /**
     * Persists a fresh enrollment, overwriting any previous credentials.
     *
     * @return true when the enrollment is durably persisted; false (e.g. a
     * failed `commit()`) means the credentials must NOT be treated as saved.
     */
    fun saveEnrollment(
        baseUrl: String,
        deviceId: String,
        deviceToken: String,
        deviceKey: String,
    ): Boolean

    /**
     * Persists the human device name sent with the enroll call, for
     * human-readable status copy (never shown as an id). Called after a
     * successful [saveEnrollment]; default no-op so JVM fakes stay
     * source-compatible.
     */
    fun saveDeviceName(deviceName: String) {}
}
