package com.phonecompanion.app.ui

import com.phonecompanion.app.data.EnrollRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import com.phonecompanion.app.data.ApiService
import com.phonecompanion.app.data.EnrollRequest
import com.phonecompanion.app.data.EnrollResponse
import com.phonecompanion.app.data.EnrollmentStore
import com.phonecompanion.app.data.FcmTokenUpdateRequest
import com.phonecompanion.app.data.FcmTokenUpdateResponse

/**
 * Finding 4/5: rotation-safe enroll. The ViewModel survives recreation; an
 * in-flight enroll is never duplicated and status restores from the flow.
 */
class PairViewModelTest {

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

    private class FakeApi(
        private vararg val responses: Response<EnrollResponse>,
        private val latencyMs: Long = 0,
    ) : ApiService {
        var enrollCalls = 0
        override suspend fun enroll(request: EnrollRequest): Response<EnrollResponse> {
            enrollCalls++
            if (latencyMs > 0) delay(latencyMs)
            return responses[minOf(enrollCalls - 1, responses.size - 1)]
        }

        override suspend fun updateFcmToken(request: FcmTokenUpdateRequest): Response<FcmTokenUpdateResponse> =
            Response.success(FcmTokenUpdateResponse(updated = true))

        override suspend fun getWidgetsRaw(): Response<ResponseBody> =
            throw UnsupportedOperationException("not under test")
    }

    private fun ok() = Response.success(
        EnrollResponse(deviceId = "dev-1", deviceToken = "tok", deviceKey = "key"),
    )

    private fun err(code: Int) = Response.error<EnrollResponse>(
        code,
        """{"error":"gone"}""".toResponseBody("application/json".toMediaType()),
    )

    private fun model(api: FakeApi, store: FakeStore = FakeStore()): PairViewModel =
        PairViewModel(
            repository = EnrollRepository(store) { _ -> api },
            fcmTokenProvider = { null },
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun `second start while busy does not enroll twice`() = runBlocking {
        val api = FakeApi(ok(), latencyMs = 50)
        val vm = model(api)
        vm.startEnroll("https://example.com", "tok", "Pixel")
        vm.startEnroll("https://example.com", "tok", "Pixel")
        // Let the single in-flight enroll finish (Unconfined runs eagerly).
        var waited = 0
        while (api.enrollCalls == 0 && waited < 50) {
            delay(10)
            waited++
        }
        // Collect terminal state.
        var terminal = vm.state.value
        waited = 0
        while (terminal.busy && waited < 100) {
            delay(10)
            terminal = vm.state.value
            waited++
        }
        assertEquals(1, api.enrollCalls)
        assertEquals(PairMessages.SUCCESS, terminal.messageKey)
    }

    @Test
    fun `success carries human name and normalized url, never the device id`() = runBlocking {
        val api = FakeApi(ok())
        val store = FakeStore()
        val vm = model(api, store)
        vm.startEnroll("https://example.com", "tok", "My Phone")
        // Unconfined: state is terminal by the time startEnroll returns.
        var terminal = vm.state.value
        var waited = 0
        while (terminal.busy && waited < 100) {
            delay(10)
            terminal = vm.state.value
            waited++
        }
        val enrolled = terminal.enrolled!!
        assertEquals("My Phone", enrolled.deviceName)
        // Normalized exactly like the persisted credential (trailing slash).
        assertEquals("https://example.com/", enrolled.serverUrl)
        assertEquals("My Phone", store.deviceName)
    }

    @Test
    fun `blank device name falls back to the default`() = runBlocking {
        val api = FakeApi(ok())
        val vm = model(api)
        vm.startEnroll("https://example.com", "tok", "  ")
        var terminal = vm.state.value
        var waited = 0
        while (terminal.busy && waited < 100) {
            delay(10)
            terminal = vm.state.value
            waited++
        }
        assertEquals(EnrollRepository.DEFAULT_DEVICE_NAME, terminal.enrolled?.deviceName)
    }

    @Test
    fun `result mapping covers every user message key`() {
        assertEquals(
            PairMessages.EMPTY_TOKEN to emptyList<String>(),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.InvalidInput("x")),
        )
        assertEquals(
            PairMessages.INVALID_URL to listOf("r"),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.InvalidUrl("r")),
        )
        assertEquals(
            PairMessages.EXPIRED to emptyList<String>(),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.ExpiredOrUsed("gone")),
        )
        assertEquals(
            PairMessages.CONFLICT to emptyList<String>(),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.AlreadyEnrolled("c")),
        )
        // 422: user copy carries no code; the raw code stays debug-only.
        assertEquals(
            PairMessages.VALIDATION to emptyList<String>(),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.ValidationError("malformed")),
        )
        assertTrue(
            PairViewModel.debugDetailFor(EnrollRepository.Result.ValidationError("malformed"))!!
                .contains("malformed"),
        )
        assertEquals(
            PairMessages.NETWORK to listOf("down"),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.NetworkError("down")),
        )
        assertEquals(
            PairMessages.PERSIST_FAILED to emptyList<String>(),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.Unexpected("persist-failed")),
        )
        assertEquals(
            PairMessages.UNEXPECTED to listOf("boom"),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.Unexpected("boom")),
        )
        // Server errors expose only the HTTP status, never the raw backend code.
        assertEquals(
            PairMessages.SERVER to listOf("500"),
            PairViewModel.messageForEnrollResult(EnrollRepository.Result.ServerError(500, "gone")),
        )
        assertTrue(
            PairViewModel.debugDetailFor(EnrollRepository.Result.ServerError(500, "gone"))!!
                .contains("gone"),
        )
    }

    @Test
    fun `422 maps distinctly from generic server errors`() = runBlocking {
        val api = FakeApi(err(422))
        val vm = model(api)
        vm.startEnroll("https://example.com", "tok", "d")
        var terminal = vm.state.value
        var waited = 0
        while (terminal.busy && waited < 100) {
            delay(10)
            terminal = vm.state.value
            waited++
        }
        assertEquals(PairMessages.VALIDATION, terminal.messageKey)
        assertTrue(terminal.debugDetail!!.contains("422"))
    }

    @Test
    fun `invalid qr reasons bucket into action-oriented keys`() {
        assertEquals(
            PairMessages.INVALID_QR_NO_TOKEN,
            PairViewModel.messageForInvalidQr("QR JSON has no enroll token"),
        )
        assertEquals(
            PairMessages.INVALID_QR_NO_TOKEN,
            PairViewModel.messageForInvalidQr("QR link has an empty enroll token"),
        )
        assertEquals(
            PairMessages.INVALID_QR_EMPTY,
            PairViewModel.messageForInvalidQr("empty QR code"),
        )
        assertEquals(
            PairMessages.INVALID_QR_UNRECOGNIZED,
            PairViewModel.messageForInvalidQr("unrecognized QR content"),
        )
    }

    @Test
    fun `display server url matches persisted normalization`() {
        assertEquals("https://example.com/", PairViewModel.displayServerUrl("example.com"))
        assertEquals("https://example.com/", PairViewModel.displayServerUrl("https://example.com"))
        assertEquals("https://example.com/", PairViewModel.displayServerUrl("  https://example.com  "))
    }

    @Test
    fun `scanned status branches on base url presence`() {
        val vm = model(FakeApi(ok()))
        vm.onQrScanned(baseUrlBlank = true)
        assertEquals(PairMessages.SCANNED_ENTER_URL, vm.state.value.messageKey)
        assertNull(vm.state.value.debugDetail)
        vm.onQrScanned(baseUrlBlank = false)
        assertEquals(PairMessages.SCANNED_CHECK_URL, vm.state.value.messageKey)
    }

    @Test
    fun `throwing fcm token provider fails closed, enroll still succeeds`() = runBlocking {
        // Mirrors PairActivity.fetchFcmToken with Firebase unconfigured
        // (IllegalStateException from FirebaseMessaging): enroll proceeds
        // with an empty token instead of crashing.
        val api = FakeApi(ok())
        val vm = PairViewModel(
            repository = EnrollRepository(FakeStore()) { _ -> api },
            fcmTokenProvider = { throw IllegalStateException("FirebaseApp not initialized") },
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )
        vm.startEnroll("https://example.com", "tok", "My Phone")
        var terminal = vm.state.value
        var waited = 0
        while (terminal.busy && waited < 100) {
            delay(10)
            terminal = vm.state.value
            waited++
        }
        assertEquals(PairMessages.SUCCESS, terminal.messageKey)
        assertEquals(1, api.enrollCalls)
    }
}
