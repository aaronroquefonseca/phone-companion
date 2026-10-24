package com.phonecompanion.app.data

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Runs `POST /api/v1/enroll` and persists the issued credentials to an
 * [EnrollmentStore] (production: [SecureStorage]). Maps HTTP/transport
 * failures to a closed set of results so
 * [com.phonecompanion.app.ui.PairActivity] can show precise error states.
 * Call from a background dispatcher; Retrofit suspending calls do the I/O.
 *
 * A failed persist (`saveEnrollment` returns false) is reported as
 * [Result.Unexpected] `"persist-failed"` — never as success.
 */
class EnrollRepository(
    private val storage: EnrollmentStore,
    /**
     * Invoked once per successful enroll, after the credentials durably
     * persist. Production passes a lambda that clears the revocation
     * marker ([com.phonecompanion.app.work.PollStateStore.revokedAtMs]) —
     * a re-pair heals a previously revoked device. Never invoked on any
     * failure (including `"persist-failed"`).
     *
     * Kept ahead of [serviceFactory] so existing trailing-lambda call
     * sites (`EnrollRepository(store) { _ -> api }`) keep binding to the
     * factory; pass this one by name.
     */
    private val onEnrolled: () -> Unit = {},
    private val serviceFactory: (String) -> ApiService = { baseUrl -> ApiClient.create(baseUrl) },
) {

    sealed interface Result {
        data class Success(val deviceId: String) : Result
        /** HTTP 410: single-use token expired or already consumed. */
        data class ExpiredOrUsed(val error: String?) : Result
        /** HTTP 409: device already enrolled (re-pair flow). */
        data class AlreadyEnrolled(val error: String?) : Result
        data class ServerError(val httpCode: Int, val error: String?) : Result
        data class NetworkError(val message: String?) : Result
        data class InvalidUrl(val reason: String) : Result
        data class InvalidInput(val reason: String) : Result
        /**
         * HTTP 422: the server rejected the request as malformed. Carries the
         * raw backend `error` code for logcat only — UI copy must never show
         * it (see PairViewModel message mapping).
         */
        data class ValidationError(val error: String?) : Result
        data class Unexpected(val message: String?) : Result
    }

    suspend fun enroll(
        rawBaseUrl: String,
        enrollToken: String,
        deviceName: String,
        fcmToken: String?,
    ): Result {
        if (enrollToken.isBlank()) return Result.InvalidInput("Enroll token is empty")
        val baseUrl = try {
            ApiClient.normalizeBaseUrl(rawBaseUrl)
        } catch (e: IllegalArgumentException) {
            return Result.InvalidUrl(e.message ?: "Invalid server URL")
        }

        // Enroll authenticates with the single-use token in the body, so no
        // Bearer token is attached to this client. Client construction stays
        // inside the try: Retrofit validates the URL eagerly and throws
        // IllegalArgumentException for malformed input (e.g. bare "https://").
        return try {
            val service = serviceFactory(baseUrl)
            val sentDeviceName = deviceName.ifBlank { DEFAULT_DEVICE_NAME }
            val response = service.enroll(
                EnrollRequest(
                    enrollToken = enrollToken.trim(),
                    deviceName = sentDeviceName,
                    fcmToken = fcmToken,
                ),
            )
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.ServerError(response.code(), "empty-body")
                if (body.deviceId.isBlank() || body.deviceToken.isBlank() || body.deviceKey.isBlank()) {
                    return Result.ServerError(response.code(), "empty-credentials")
                }
                val persisted = storage.saveEnrollment(
                    baseUrl,
                    body.deviceId,
                    body.deviceToken,
                    body.deviceKey,
                )
                if (!persisted) return Result.Unexpected("persist-failed")
                storage.saveDeviceName(sentDeviceName)
                onEnrolled()
                Result.Success(body.deviceId)
            } else {
                val code = ApiClient.parseErrorCode(
                    runCatching { response.errorBody()?.string() }.getOrNull(),
                )
                when (response.code()) {
                    410 -> Result.ExpiredOrUsed(code)
                    409 -> Result.AlreadyEnrolled(code)
                    422 -> Result.ValidationError(code)
                    else -> Result.ServerError(response.code(), code)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Result.InvalidUrl(e.message ?: "Invalid server URL")
        } catch (e: IOException) {
            Result.NetworkError(e.message)
        } catch (e: Exception) {
            Result.Unexpected(e.message)
        }
    }

    companion object {
        const val DEFAULT_DEVICE_NAME = "Android phone"
    }
}
