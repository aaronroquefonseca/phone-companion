package com.phonecompanion.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Finding 9: HTTP 422 maps to its own user-safe result (never a raw
 * backend code), and the sent device name persists for human status copy.
 */
class EnrollValidationTest {

    private class FakeStore : EnrollmentStore {
        var deviceName: String? = null
        override fun saveEnrollment(
            baseUrl: String,
            deviceId: String,
            deviceToken: String,
            deviceKey: String,
        ): Boolean = true

        override fun saveDeviceName(deviceName: String) {
            this.deviceName = deviceName
        }
    }

    private class FakeApi(private val code: Int) : ApiService {
        var sawName: String? = null
        override suspend fun enroll(request: EnrollRequest): Response<EnrollResponse> {
            sawName = request.deviceName
            if (code == 200) {
                return Response.success(
                    EnrollResponse(deviceId = "dev-1", deviceToken = "tok", deviceKey = "key"),
                )
            }
            return Response.error(
                code,
                """{"error":"malformed_request"}""".toResponseBody("application/json".toMediaType()),
            )
        }

        override suspend fun updateFcmToken(request: FcmTokenUpdateRequest): Response<FcmTokenUpdateResponse> =
            Response.success(FcmTokenUpdateResponse(updated = true))

        override suspend fun getWidgetsRaw(): Response<ResponseBody> =
            throw UnsupportedOperationException("not under test")
    }

    @Test
    fun `422 maps to ValidationError without surfacing the backend code`() = runBlocking {
        val repo = EnrollRepository(FakeStore()) { _ -> FakeApi(422) }
        val result = repo.enroll("https://example.com", "tok", "Pixel", null)
        assertTrue(result is EnrollRepository.Result.ValidationError)
        assertEquals("malformed_request", (result as EnrollRepository.Result.ValidationError).error)
    }

    @Test
    fun `success persists the sent device name`() = runBlocking {
        val store = FakeStore()
        val api = FakeApi(200)
        val repo = EnrollRepository(store) { _ -> api }
        val result = repo.enroll("https://example.com", "tok", "My Phone", null)
        assertTrue(result is EnrollRepository.Result.Success)
        assertEquals("My Phone", store.deviceName)
        assertEquals("My Phone", api.sawName)
    }

    @Test
    fun `blank device name persists the default`() = runBlocking {
        val store = FakeStore()
        val api = FakeApi(200)
        val repo = EnrollRepository(store) { _ -> api }
        repo.enroll("https://example.com", "tok", "   ", null)
        assertEquals(EnrollRepository.DEFAULT_DEVICE_NAME, store.deviceName)
        assertEquals(EnrollRepository.DEFAULT_DEVICE_NAME, api.sawName)
    }
}
