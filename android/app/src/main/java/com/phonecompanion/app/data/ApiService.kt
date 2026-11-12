package com.phonecompanion.app.data

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

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

    /**
     * Poll server-offered commands for this device (poll-triggered
     * install path, no FCM). Requires the device Bearer token.
     *
     * Raw body like [getWidgetsRaw]: entries parse through
     * [com.phonecompanion.app.notify.AppInstallCommands] with the same
     * lenient-unknown-kinds / strict-known-fields discipline as the
     * notification parser (unknown kinds acked-and-ignored with
     * shape-only logging, malformed entries skipped with counts).
     * Each entry carries the per-device `command_id` the ack body
     * references (see [CommandsAckRequest]).
     */
    @GET("api/v1/outbox")
    suspend fun getCommandsRaw(): Response<ResponseBody>

    /**
     * Long-poll the outbox: returns `{"events":["outbox"]}` immediately
     * when the device has pending work, else after up to 300 s. Used by
     * instant mode (the persistent foreground service) so the operator
     * never depends on FCM wake delivery while the phone is idle.
     * Requires the device Bearer token; the client must allow a >300 s
     * read timeout (see [ApiClient.createLongPoll]).
     */
    @GET("api/v1/events/wait")
    suspend fun waitForEventsRaw(): Response<ResponseBody>

    /**
     * Acknowledge the caller's own command ids so the server stops
     * re-offering them. Idempotent server-side: re-acks are harmless
     * no-ops (unknown/foreign ids are silently ignored), so a failed
     * ack is safely retried on the next poll.
     * Requires the device Bearer token.
     */
    @POST("api/v1/commands/ack")
    suspend fun ackCommands(@Body request: CommandsAckRequest): Response<ResponseBody>

    /**
     * Report one remote-shell command outcome. Device Bearer only; the
     * server stores it for the operator to read and ignores unknown or
     * already-reported ids.
     */
    @POST("api/v1/shell/result")
    suspend fun postShellResult(@Body request: ShellResultRequest): Response<ResponseBody>

    /**
     * Hold the fast link open. The server streams one NDJSON command per
     * line and expects the answer as a raw-body POST to [postLinkResult].
     * 404 means the backend predates the link and the caller should fall
     * back to the outbox long poll.
     */
    @Streaming
    @GET("api/v1/device/link")
    suspend fun deviceLink(): Response<ResponseBody>

    /**
     * Return one link command's result. The body is the payload itself
     * (image, file, text), not JSON, so nothing is base64'd or capped at
     * the outbox's 64 KB.
     */
    @POST("api/v1/device/result/{opId}")
    suspend fun postLinkResult(
        @Path("opId") opId: String,
        @Header("X-Result-Type") contentType: String,
        @Header("X-Result-Status") status: Int,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}
