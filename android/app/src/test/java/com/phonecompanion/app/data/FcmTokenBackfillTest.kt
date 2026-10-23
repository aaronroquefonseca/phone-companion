package com.phonecompanion.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Wave-2 push: FCM token backfill upload behavior.
 *
 * [FcmTokenBackfill.upload] is the pure, Context-free seam — success maps
 * to true, every rejection or transport failure to false, and cancellation
 * propagates. (The Context-wired [FcmTokenBackfill.maybeBackfill] needs
 * `EncryptedSharedPreferences`, so it is covered on-device, not here.)
 * Fake tokens only — no real registration tokens in tests.
 */
class FcmTokenBackfillTest {

    private class FakeApi(
        private val updateFn: suspend (FcmTokenUpdateRequest) -> Response<FcmTokenUpdateResponse>,
    ) : ApiService {
        var updateCalls = 0
        var sawToken: String? = null

        override suspend fun enroll(request: EnrollRequest): Response<EnrollResponse> =
            throw UnsupportedOperationException("not under test")

        override suspend fun updateFcmToken(request: FcmTokenUpdateRequest): Response<FcmTokenUpdateResponse> {
            updateCalls++
            sawToken = request.fcmToken
            return updateFn(request)
        }

        override suspend fun getWidgets(): Response<WidgetsResponse> =
            throw UnsupportedOperationException("not under test")
    }

    private fun errorResponse(code: Int): Response<FcmTokenUpdateResponse> =
        Response.error(code, """{"error":"unauthorized"}""".toResponseBody("application/json".toMediaType()))

    @Test
    fun `2xx with updated true uploads the token`() = runBlocking {
        val api = FakeApi { Response.success(FcmTokenUpdateResponse(updated = true)) }
        assertTrue(FcmTokenBackfill.upload(api, "fake-fcm-token-1"))
        assertEquals(1, api.updateCalls)
        assertEquals("fake-fcm-token-1", api.sawToken)
    }

    @Test
    fun `2xx with updated false still counts as uploaded`() = runBlocking {
        val api = FakeApi { Response.success(FcmTokenUpdateResponse(updated = false)) }
        assertTrue(FcmTokenBackfill.upload(api, "fake-fcm-token-1"))
    }

    @Test
    fun `rejection maps to false without throwing`() = runBlocking {
        val api = FakeApi { errorResponse(401) }
        assertFalse(FcmTokenBackfill.upload(api, "fake-fcm-token-1"))
        assertEquals(1, api.updateCalls)
    }

    @Test
    fun `transport failure maps to false without throwing`() = runBlocking {
        val api = FakeApi { throw IOException("offline") }
        assertFalse(FcmTokenBackfill.upload(api, "fake-fcm-token-1"))
    }

    @Test
    fun `cancellation propagates instead of mapping to false`() = runBlocking {
        val api = FakeApi { throw CancellationException("stop") }
        try {
            FcmTokenBackfill.upload(api, "fake-fcm-token-1")
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // Expected: structured concurrency keeps working.
        }
    }
}
