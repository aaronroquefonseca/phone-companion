package com.phonecompanion.app.data

import java.io.IOException

/**
 * Runs `POST /api/v1/enroll` and persists the issued credentials to
 * [SecureStorage]. Maps HTTP/transport failures to a closed set of results
 * so [com.phonecompanion.app.ui.PairActivity] can show precise error states.
 * Call from a background dispatcher; Retrofit suspending calls do the I/O.
 */
class EnrollRepository(private val storage: SecureStorage) {

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
        // Bearer token is attached to this client.
        val service = ApiClient.create(baseUrl)
        return try {
            val response = service.enroll(
                EnrollRequest(
                    enrollToken = enrollToken.trim(),
                    deviceName = deviceName.ifBlank { DEFAULT_DEVICE_NAME },
                    fcmToken = fcmToken,
                ),
            )
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.ServerError(response.code(), "empty-body")
                if (body.deviceId.isBlank() || body.deviceToken.isBlank() || body.deviceKey.isBlank()) {
                    return Result.ServerError(response.code(), "empty-credentials")
                }
                storage.saveEnrollment(baseUrl, body.deviceId, body.deviceToken, body.deviceKey)
                Result.Success(body.deviceId)
            } else {
                val code = ApiClient.parseErrorCode(
                    runCatching { response.errorBody()?.string() }.getOrNull(),
                )
                when (response.code()) {
                    410 -> Result.ExpiredOrUsed(code)
                    409 -> Result.AlreadyEnrolled(code)
                    else -> Result.ServerError(response.code(), code)
                }
            }
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
