package com.phonecompanion.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * Fix batch B: enroll persistence + cancellation.
 *
 * A failed `commit()` must surface as `Unexpected("persist-failed")` —
 * never as success — and coroutine cancellation must propagate instead of
 * being mapped to a result.
 */
class EnrollRepositoryTest {

    private data class Saved(
        val baseUrl: String,
        val deviceId: String,
        val deviceToken: String,
        val deviceKey: String,
    )

    private class FakeStore(var persistResult: Boolean = true) : EnrollmentStore {
        var saved: Saved? = null
        override fun saveEnrollment(
            baseUrl: String,
            deviceId: String,
            deviceToken: String,
            deviceKey: String,
        ): Boolean {
            saved = Saved(baseUrl, deviceId, deviceToken, deviceKey)
            return persistResult
        }
    }

    private class FakeApi(
        private val enrollFn: suspend (EnrollRequest) -> Response<EnrollResponse>,
    ) : ApiService {
        var enrollCalls = 0
        override suspend fun enroll(request: EnrollRequest): Response<EnrollResponse> {
            enrollCalls++
            return enrollFn(request)
        }

        override suspend fun updateFcmToken(request: FcmTokenUpdateRequest): Response<FcmTokenUpdateResponse> =
            Response.success(FcmTokenUpdateResponse(updated = true))

        override suspend fun getWidgets(): Response<WidgetsResponse> =
            Response.success(WidgetsResponse())
    }

    private fun okResponse() = Response.success(
        EnrollResponse(deviceId = "dev-1", deviceToken = "tok", deviceKey = "key"),
    )

    private fun errorResponse(code: Int): Response<EnrollResponse> =
        Response.error(code, """{"error":"gone"}""".toResponseBody("application/json".toMediaType()))

    @Test
    fun `success persists normalized url and returns device id`() = runBlocking {
        val store = FakeStore()
        var sawRequest: EnrollRequest? = null
        val repo = EnrollRepository(store) { _ ->
            FakeApi { req -> sawRequest = req; okResponse() }
        }
        val result = repo.enroll("https://example.com", "tok123", "Pixel", null)
        assertEquals(EnrollRepository.Result.Success("dev-1"), result)
        assertEquals(
            Saved("https://example.com/", "dev-1", "tok", "key"),
            store.saved,
        )
        assertEquals("tok123", sawRequest?.enrollToken)
    }

    @Test
    fun `failed persist maps to unexpected persist-failed`() = runBlocking {
        val store = FakeStore(persistResult = false)
        val repo = EnrollRepository(store) { _ -> FakeApi { okResponse() } }
        val result = repo.enroll("https://example.com", "tok123", "Pixel", null)
        assertEquals(EnrollRepository.Result.Unexpected("persist-failed"), result)
    }

    @Test
    fun `cancellation propagates instead of mapping to a result`() = runBlocking {
        val repo = EnrollRepository(FakeStore()) { _ ->
            FakeApi { throw CancellationException("stop") }
        }
        try {
            repo.enroll("https://example.com", "tok123", "Pixel", null)
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // Expected: structured concurrency keeps working.
        }
    }

    @Test
    fun `expired and conflict codes map distinctly`() = runBlocking {
        val gone = EnrollRepository(FakeStore()) { _ -> FakeApi { errorResponse(410) } }
        assertTrue(gone.enroll("https://example.com", "t", "d", null) is EnrollRepository.Result.ExpiredOrUsed)
        val conflict = EnrollRepository(FakeStore()) { _ -> FakeApi { errorResponse(409) } }
        assertTrue(conflict.enroll("https://example.com", "t", "d", null) is EnrollRepository.Result.AlreadyEnrolled)
    }

    @Test
    fun `empty credentials body is a server error without persisting`() = runBlocking {
        val store = FakeStore()
        val repo = EnrollRepository(store) { _ ->
            FakeApi {
                Response.success(EnrollResponse(deviceId = "", deviceToken = "", deviceKey = ""))
            }
        }
        val result = repo.enroll("https://example.com", "t", "d", null)
        assertEquals(EnrollRepository.Result.ServerError(200, "empty-credentials"), result)
        assertEquals(null, store.saved)
    }

    @Test
    fun `blank token is invalid input before any network call`() = runBlocking {
        val api = FakeApi { okResponse() }
        val repo = EnrollRepository(FakeStore()) { _ -> api }
        val result = repo.enroll("https://example.com", "  ", "d", null)
        assertTrue(result is EnrollRepository.Result.InvalidInput)
        assertEquals(0, api.enrollCalls)
    }
}
