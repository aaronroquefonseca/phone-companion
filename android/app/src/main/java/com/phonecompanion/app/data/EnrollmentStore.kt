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
}
