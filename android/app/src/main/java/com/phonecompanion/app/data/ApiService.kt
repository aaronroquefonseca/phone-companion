package com.phonecompanion.app.data

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Backend API surface per docs/api-contract.md v1.
 *
 * The app authenticates with the per-device Bearer token issued at enroll
 * (see [ApiClient]); the `POST /api/v1/notify` fan-out endpoint is
 * intentionally absent here — it takes the operator WebUI session, never a
 * device token, so the app has no business calling it.
 */
interface ApiService {

    /**
     * Enroll this phone. Auth is the single-use enroll token in the body
     * (scanned from the WebUI pairing QR), NOT a device Bearer token —
     * call this on a client built without a token provider.
     * Success: HTTP 201. Failures: 410 expired/used token, 409 already
     * enrolled, 422 malformed request.
     */
    @POST("api/v1/enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<EnrollResponse>

    /**
     * Upload the current FCM registration token for this device (Wave-2
     * push backfill). Auth is the device Bearer token. Idempotent: the
     * server rewrites the stored token only when it differs and answers
     * `{"updated": ...}` either way. Best-effort from the app — failures
     * are retried on the next widgets poll.
     */
    @POST("api/v1/device/fcm-token")
    suspend fun updateFcmToken(@Body request: FcmTokenUpdateRequest): Response<FcmTokenUpdateResponse>

    /**
     * Poll widget definitions for this device. Requires the device Bearer
     * token. Response parsing/rendering per type is the widgets PR's job.
     *
     * Raw body (not a Moshi-converted [WidgetsResponse]) so the poll path
     * parses through [AppMoshi] — the exact instance the parse-path
     * regression test pins — and can log the response byte length when
     * parsing fails (shape only, never content).
     */
    @GET("api/v1/widgets")
    suspend fun getWidgetsRaw(): Response<ResponseBody>
}
