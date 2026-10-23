package com.phonecompanion.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.phonecompanion.app.R
import com.phonecompanion.app.data.EnrollRepository
import com.phonecompanion.app.data.QrParse
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.parsePairingQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairing screen: scans the WebUI pairing QR (ML Kit code scanner, no
 * camera boilerplate), POSTs `/api/v1/enroll` over HTTPS, and persists the
 * issued credentials to [SecureStorage]. All failure modes surface as
 * status text — nothing crashes, nothing is logged with secrets.
 */
class PairActivity : AppCompatActivity() {

    private lateinit var storage: SecureStorage
    private lateinit var baseUrlEdit: EditText
    private lateinit var deviceNameEdit: EditText
    private lateinit var enrollTokenEdit: EditText
    private lateinit var scanButton: Button
    private lateinit var enrollButton: Button
    private lateinit var statusView: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)

        storage = SecureStorage.getInstance(this)
        baseUrlEdit = findViewById(R.id.baseUrlEdit)
        deviceNameEdit = findViewById(R.id.deviceNameEdit)
        enrollTokenEdit = findViewById(R.id.enrollTokenEdit)
        scanButton = findViewById(R.id.scanButton)
        enrollButton = findViewById(R.id.enrollButton)
        statusView = findViewById(R.id.pairStatus)

        baseUrlEdit.setText(storage.baseUrl.orEmpty())
        if (deviceNameEdit.text.isBlank()) {
            deviceNameEdit.setText(Build.MODEL)
        }

        scanButton.setOnClickListener { startScan() }
        enrollButton.setOnClickListener { startEnroll() }
    }

    private fun startScan() {
        setStatus(getString(R.string.pair_opening_scanner))
        GmsBarcodeScanning.getClient(this)
            .startScan()
            .addOnSuccessListener { barcode ->
                when (val parsed = parsePairingQr(barcode.rawValue)) {
                    is QrParse.Ok -> {
                        if (parsed.payload.baseUrl.isNotBlank()) {
                            baseUrlEdit.setText(parsed.payload.baseUrl)
                        }
                        enrollTokenEdit.setText(parsed.payload.enrollToken)
                        setStatus(getString(R.string.pair_scanned_tap_enroll))
                    }
                    is QrParse.Invalid ->
                        setStatus(getString(R.string.pair_invalid_qr, parsed.reason))
                }
            }
            .addOnCanceledListener { setStatus(getString(R.string.pair_scan_cancelled)) }
            .addOnFailureListener { e ->
                setStatus(getString(R.string.pair_scanner_unavailable, e.message ?: e.toString()))
            }
    }

    private fun startEnroll() {
        if (busy) return
        busy = true
        setButtonsEnabled(false)
        setStatus(getString(R.string.pair_enrolling))

        val rawBaseUrl = baseUrlEdit.text.toString()
        val token = enrollTokenEdit.text.toString()
        val deviceName = deviceNameEdit.text.toString().ifBlank { Build.MODEL }

        lifecycleScope.launch {
            val fcmToken = fetchFcmToken()
            val result = withContext(Dispatchers.IO) {
                EnrollRepository(storage).enroll(rawBaseUrl, token, deviceName, fcmToken)
            }
            busy = false
            setButtonsEnabled(true)
            when (result) {
                is EnrollRepository.Result.Success -> {
                    setStatus(getString(R.string.pair_success, result.deviceId))
                    startActivity(Intent(this@PairActivity, MainActivity::class.java))
                    finish()
                }
                is EnrollRepository.Result.ExpiredOrUsed ->
                    setStatus(getString(R.string.pair_error_expired))
                is EnrollRepository.Result.AlreadyEnrolled ->
                    setStatus(getString(R.string.pair_error_conflict))
                is EnrollRepository.Result.InvalidUrl ->
                    setStatus(getString(R.string.pair_error_url, result.reason))
                is EnrollRepository.Result.InvalidInput ->
                    setStatus(result.reason)
                is EnrollRepository.Result.NetworkError ->
                    setStatus(
                        getString(
                            R.string.pair_error_network,
                            result.message ?: getString(R.string.pair_error_network_default),
                        ),
                    )
                is EnrollRepository.Result.ServerError ->
                    setStatus(
                        getString(
                            R.string.pair_error_server,
                            result.httpCode,
                            result.error ?: getString(R.string.pair_error_unknown),
                        ),
                    )
                is EnrollRepository.Result.Unexpected ->
                    setStatus(
                        getString(
                            R.string.pair_error_unexpected,
                            result.message ?: getString(R.string.pair_error_unknown),
                        ),
                    )
            }
        }
    }

    /**
     * Best-effort FCM token for the enroll body. Null when push is not
     * configured (no google-services.json in dev) or Play services is
     * unreachable — enroll proceeds without it.
     */
    private suspend fun fetchFcmToken(): String? {
        return try {
            withTimeoutOrNull(FCM_TOKEN_TIMEOUT_MS) {
                FirebaseMessaging.getInstance().token.await()
            }?.also { storage.fcmToken = it } ?: storage.fcmToken
        } catch (_: Exception) {
            storage.fcmToken
        }
    }

    private fun setStatus(text: CharSequence) {
        statusView.text = text
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        scanButton.isEnabled = enabled
        enrollButton.isEnabled = enabled
    }

    companion object {
        private const val FCM_TOKEN_TIMEOUT_MS = 10_000L
    }
}
