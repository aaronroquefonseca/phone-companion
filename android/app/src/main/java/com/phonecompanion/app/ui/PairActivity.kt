package com.phonecompanion.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.phonecompanion.app.R
import com.phonecompanion.app.data.EnrollRepository
import com.phonecompanion.app.data.QrParse
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.parsePairingQr
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairing screen: scans the WebUI pairing QR (Play services code scanner —
 * Google's own scanner activity, so this app needs no CAMERA permission and
 * no camera boilerplate), POSTs `/api/v1/enroll` over HTTPS, and persists
 * the issued credentials to [SecureStorage]. All failure modes surface as
 * action-oriented status text with next steps — nothing crashes, nothing is
 * logged with secrets (raw backend details go to logcat only).
 *
 * Rotation-safe: [PairViewModel] holds the enroll job and the latest status,
 * so rotating mid-enroll neither duplicates the request nor loses feedback.
 * On success the app navigates to [MainActivity] with
 * [MainActivity.EXTRA_JUST_PAIRED], which renders the human confirmation
 * (device name + server, never the raw device id) behind an explicit
 * Continue.
 */
class PairActivity : AppCompatActivity() {

    private lateinit var storage: SecureStorage
    private lateinit var baseUrlEdit: EditText
    private lateinit var deviceNameEdit: EditText
    private lateinit var enrollTokenEdit: EditText
    private lateinit var scanButton: Button
    private lateinit var pairButton: Button
    private lateinit var statusView: TextView

    private val model: PairViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repo = EnrollRepository(storage)
                return PairViewModel(
                    repository = repo,
                    fcmTokenProvider = { fetchFcmToken() },
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)

        storage = SecureStorage.getInstance(this)
        baseUrlEdit = findViewById(R.id.baseUrlEdit)
        deviceNameEdit = findViewById(R.id.deviceNameEdit)
        enrollTokenEdit = findViewById(R.id.enrollTokenEdit)
        scanButton = findViewById(R.id.scanButton)
        pairButton = findViewById(R.id.pairButton)
        statusView = findViewById(R.id.pairStatus)

        if (savedInstanceState == null) {
            // Fresh open only: view state (and the ViewModel status flow)
            // restore everything across rotation.
            baseUrlEdit.setText(storage.baseUrl.orEmpty())
            if (deviceNameEdit.text.isBlank()) {
                deviceNameEdit.setText(Build.MODEL)
            }
        }

        scanButton.setOnClickListener { startScan() }
        pairButton.setOnClickListener {
            model.startEnroll(
                baseUrlEdit.text.toString(),
                enrollTokenEdit.text.toString(),
                deviceNameEdit.text.toString(),
            )
        }

        lifecycleScope.launch {
            model.state.collect { state ->
                pairButton.isEnabled = !state.busy
                scanButton.isEnabled = !state.busy
                state.messageKey?.let { statusView.text = resolveMessage(it, state.messageArgs) }
                state.debugDetail?.let { Log.d(TAG, it) }
                state.enrolled?.let { enrolled ->
                    model.consumeEnrolled()
                    onEnrolled(enrolled)
                }
            }
        }
    }

    private fun startScan() {
        model.onOpeningScanner()
        GmsBarcodeScanning.getClient(this)
            .startScan()
            .addOnSuccessListener { barcode ->
                when (val parsed = parsePairingQr(barcode.rawValue)) {
                    is QrParse.Ok -> {
                        if (parsed.payload.baseUrl.isNotBlank()) {
                            baseUrlEdit.setText(parsed.payload.baseUrl)
                        }
                        enrollTokenEdit.setText(parsed.payload.enrollToken)
                        model.onQrScanned(parsed.payload.baseUrl.isBlank())
                    }
                    is QrParse.Invalid -> model.onQrInvalid(parsed.reason)
                }
            }
            .addOnCanceledListener { model.onScanCancelled() }
            .addOnFailureListener { e ->
                Log.d(TAG, "scanner failure: ${e.message}", e)
                model.onScannerFailed(e.message)
            }
    }

    private fun onEnrolled(enrolled: PairViewModel.Enrolled) {
        // CLEAR_TOP | SINGLE_TOP reuses the existing MainActivity instead of
        // stacking a second launcher (see MainActivity.onNewIntent).
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_JUST_PAIRED, true)
            .putExtra(MainActivity.EXTRA_JUST_PAIRED_NAME, enrolled.deviceName)
            .putExtra(MainActivity.EXTRA_JUST_PAIRED_SERVER, enrolled.serverUrl)
        startActivity(intent)
        finish()
    }

    private fun resolveMessage(key: String, args: List<String>): CharSequence = when (key) {
        PairMessages.SCANNED_CHECK_URL -> getString(R.string.pair_scanned_tap_enroll)
        PairMessages.SCANNED_ENTER_URL -> getString(R.string.pair_scanned_enter_url)
        PairMessages.SCAN_CANCELLED -> getString(R.string.pair_scan_cancelled)
        PairMessages.SCANNER_UNAVAILABLE -> getString(R.string.pair_scanner_unavailable)
        PairMessages.INVALID_QR_NO_TOKEN -> getString(R.string.pair_invalid_qr_no_token)
        PairMessages.INVALID_QR_UNRECOGNIZED -> getString(R.string.pair_invalid_qr_unrecognized)
        PairMessages.INVALID_QR_EMPTY -> getString(R.string.pair_invalid_qr_empty)
        PairMessages.EMPTY_TOKEN -> getString(R.string.pair_error_empty_token)
        PairMessages.INVALID_URL ->
            getString(R.string.pair_error_url, args.firstOrNull().orEmpty())
        PairMessages.EXPIRED -> getString(R.string.pair_error_expired)
        PairMessages.CONFLICT -> getString(R.string.pair_error_conflict)
        PairMessages.VALIDATION -> getString(R.string.pair_error_validation)
        PairMessages.PERSIST_FAILED -> getString(R.string.pair_error_persist)
        PairMessages.NETWORK -> getString(
            R.string.pair_error_network,
            args.firstOrNull().ifNullOrBlank(getString(R.string.pair_error_network_default)),
        )
        PairMessages.SERVER -> getString(
            R.string.pair_error_server,
            args.firstOrNull()?.toIntOrNull() ?: 0,
        )
        PairMessages.ENROLLING -> getString(R.string.pair_pairing)
        PairMessages.OPENING_SCANNER -> getString(R.string.pair_opening_scanner)
        else -> getString(
            R.string.pair_error_unexpected,
            args.firstOrNull().ifNullOrBlank(getString(R.string.pair_error_unknown)),
        )
    }

    /**
     * Best-effort FCM token for the enroll body. Null when push is not
     * configured (no google-services.json in dev) or Play services is
     * unreachable — enroll proceeds without it.
     */
    private suspend fun fetchFcmToken(): String? {
        return try {
            withTimeoutOrNull(FCM_TOKEN_TIMEOUT_MS) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            }
        } catch (_: Exception) {
            null
        }?.also { storage.fcmToken = it } ?: storage.fcmToken
    }

    companion object {
        private const val TAG = "PairActivity"
        private const val FCM_TOKEN_TIMEOUT_MS = 10_000L
    }
}

private fun String?.ifNullOrBlank(default: String): String =
    if (this.isNullOrBlank()) default else this
