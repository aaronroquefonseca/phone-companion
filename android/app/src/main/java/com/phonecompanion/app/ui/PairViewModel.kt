package com.phonecompanion.app.ui

import androidx.lifecycle.ViewModel
import com.phonecompanion.app.data.EnrollRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pairing screen state holder: survives rotation, so an in-flight enroll is
 * never duplicated and the status line is restored after recreation.
 *
 * A single [enrollJob] guards [startEnroll]: taps (or a recreated activity)
 * while it is active are ignored instead of firing a second enroll. The
 * [state] flow replays the latest status to every new collector, so rotation
 * mid-enroll keeps showing "Pairing…" and a finished result is still
 * delivered once (via [enrolled], cleared with [consumeEnrolled]).
 *
 * All user-visible copy resolves from string keys in [PairMessages] so the
 * mapping stays JVM-unit-testable; raw backend details travel in
 * [UiState.debugDetail] for logcat only and are never rendered.
 */
class PairViewModel(
    private val repository: EnrollRepository,
    private val fcmTokenProvider: suspend () -> String? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {

    /** One-shot success payload: human name + server, never the raw device id. */
    data class Enrolled(val deviceName: String, val serverUrl: String)

    data class UiState(
        val busy: Boolean = false,
        val messageKey: String? = null,
        val messageArgs: List<String> = emptyList(),
        /** Raw failure detail for logcat only. Never shown in the UI. */
        val debugDetail: String? = null,
        val enrolled: Enrolled? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var enrollJob: Job? = null

    fun startEnroll(rawBaseUrl: String, token: String, deviceName: String) {
        if (enrollJob?.isActive == true) return
        enrollJob = scope.launch {
            _state.value = UiState(busy = true, messageKey = PairMessages.ENROLLING)
            val fcmToken = runCatching { fcmTokenProvider() }.getOrNull()
            val result = withContext(Dispatchers.IO) {
                repository.enroll(rawBaseUrl, token, deviceName, fcmToken)
            }
            val sentName = deviceName.ifBlank { EnrollRepository.DEFAULT_DEVICE_NAME }
            val mapped = messageForEnrollResult(result)
            val enrolled = (result as? EnrollRepository.Result.Success)?.let {
                Enrolled(deviceName = sentName, serverUrl = rawBaseUrl.trim())
            }
            _state.value = UiState(
                busy = false,
                messageKey = mapped.first,
                messageArgs = mapped.second,
                debugDetail = debugDetailFor(result),
                enrolled = enrolled,
            )
        }
    }

    /** QR scanned: branch the follow-up line on whether the server URL still needs typing. */
    fun onQrScanned(baseUrlBlank: Boolean) {
        _state.value = _state.value.copy(
            messageKey = if (baseUrlBlank) PairMessages.SCANNED_ENTER_URL else PairMessages.SCANNED_CHECK_URL,
            messageArgs = emptyList(),
            debugDetail = null,
        )
    }

    fun onQrInvalid(reason: String) {
        _state.value = _state.value.copy(
            messageKey = messageForInvalidQr(reason),
            messageArgs = emptyList(),
            debugDetail = "qr invalid: $reason",
        )
    }

    fun onScanCancelled() {
        _state.value = _state.value.copy(
            messageKey = PairMessages.SCAN_CANCELLED,
            messageArgs = emptyList(),
            debugDetail = null,
        )
    }

    fun onScannerFailed(cause: String?) {
        _state.value = _state.value.copy(
            messageKey = PairMessages.SCANNER_UNAVAILABLE,
            messageArgs = emptyList(),
            debugDetail = "scanner failure: $cause",
        )
    }

    fun onOpeningScanner() {
        _state.value = _state.value.copy(
            messageKey = PairMessages.OPENING_SCANNER,
            messageArgs = emptyList(),
            debugDetail = null,
        )
    }

    fun consumeEnrolled() {
        _state.value = _state.value.copy(enrolled = null)
    }

    override fun onCleared() {
        scope.cancel()
    }

    companion object {
        /**
         * Maps an enroll result to a user-message key plus display args.
         * Raw backend codes never reach the args: 422 carries no code at
         * all, and server errors expose only the HTTP status (a transport
         * fact, not a backend code) with the raw `error` string kept to
         * [debugDetailFor].
         */
        fun messageForEnrollResult(result: EnrollRepository.Result): Pair<String, List<String>> =
            when (result) {
                is EnrollRepository.Result.Success -> PairMessages.SUCCESS to emptyList()
                is EnrollRepository.Result.InvalidInput -> PairMessages.EMPTY_TOKEN to emptyList()
                is EnrollRepository.Result.InvalidUrl ->
                    PairMessages.INVALID_URL to listOf(result.reason)
                is EnrollRepository.Result.ExpiredOrUsed -> PairMessages.EXPIRED to emptyList()
                is EnrollRepository.Result.AlreadyEnrolled -> PairMessages.CONFLICT to emptyList()
                is EnrollRepository.Result.ValidationError -> PairMessages.VALIDATION to emptyList()
                is EnrollRepository.Result.NetworkError ->
                    PairMessages.NETWORK to listOf(result.message.orEmpty())
                is EnrollRepository.Result.Unexpected ->
                    if (result.message == "persist-failed") {
                        PairMessages.PERSIST_FAILED to emptyList()
                    } else {
                        PairMessages.UNEXPECTED to listOf(result.message.orEmpty())
                    }
                is EnrollRepository.Result.ServerError ->
                    PairMessages.SERVER to listOf(result.httpCode.toString())
            }

        /** Logcat-only detail for an enroll result. Never rendered. */
        fun debugDetailFor(result: EnrollRepository.Result): String? = when (result) {
            is EnrollRepository.Result.Success -> null
            is EnrollRepository.Result.InvalidInput -> "invalid input: ${result.reason}"
            is EnrollRepository.Result.InvalidUrl -> "invalid url: ${result.reason}"
            is EnrollRepository.Result.ExpiredOrUsed -> "410 expired/used: ${result.error}"
            is EnrollRepository.Result.AlreadyEnrolled -> "409 conflict: ${result.error}"
            is EnrollRepository.Result.ValidationError -> "422 validation: ${result.error}"
            is EnrollRepository.Result.NetworkError -> "network: ${result.message}"
            is EnrollRepository.Result.Unexpected -> "unexpected: ${result.message}"
            is EnrollRepository.Result.ServerError ->
                "http ${result.httpCode}: ${result.error}"
        }

        /** Buckets the parser's invalid-QR reasons into action-oriented copy keys. */
        fun messageForInvalidQr(reason: String): String = when {
            "enroll token" in reason -> PairMessages.INVALID_QR_NO_TOKEN
            reason.startsWith("empty") -> PairMessages.INVALID_QR_EMPTY
            else -> PairMessages.INVALID_QR_UNRECOGNIZED
        }
    }
}

/**
 * Stable keys for every pair-screen status line. The activity resolves each
 * key to a string resource; tests assert on the keys without Android.
 */
object PairMessages {
    const val ENROLLING = "enrolling"
    const val OPENING_SCANNER = "opening_scanner"
    const val SCANNED_CHECK_URL = "scanned_check_url"
    const val SCANNED_ENTER_URL = "scanned_enter_url"
    const val SCAN_CANCELLED = "scan_cancelled"
    const val SCANNER_UNAVAILABLE = "scanner_unavailable"
    const val INVALID_QR_NO_TOKEN = "invalid_qr_no_token"
    const val INVALID_QR_UNRECOGNIZED = "invalid_qr_unrecognized"
    const val INVALID_QR_EMPTY = "invalid_qr_empty"
    const val SUCCESS = "success"
    const val EMPTY_TOKEN = "empty_token"
    const val INVALID_URL = "invalid_url"
    const val EXPIRED = "expired"
    const val CONFLICT = "conflict"
    const val VALIDATION = "validation"
    const val NETWORK = "network"
    const val PERSIST_FAILED = "persist_failed"
    const val SERVER = "server"
    const val UNEXPECTED = "unexpected"
}
