package com.phonecompanion.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phonecompanion.app.R
import com.phonecompanion.app.data.SecureStorage

/**
 * Launcher screen: pairing status, navigation to [PairActivity] and
 * [WidgetsActivity]. Requests the notifications runtime permission on
 * Android 13+ so FCM notifications can display; shows the latest tapped
 * notification when opened from one.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dismissFromIntent(intent)

        statusView = findViewById(R.id.pairStatus)

        findViewById<Button>(R.id.pairButton).setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }
        findViewById<Button>(R.id.widgetsButton).setOnClickListener {
            startActivity(Intent(this, WidgetsActivity::class.java))
        }

        if (savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dismissFromIntent(intent)
        renderStatus()
    }

    /**
     * Action-button taps do not auto-dismiss (only content taps do), so the
     * app cancels the notification id the service attached. Harmless when
     * the tap already dismissed it.
     */
    private fun dismissFromIntent(intent: Intent) {
        if (intent.hasExtra(EXTRA_NOTIFY_INT_ID)) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.cancel(intent.getIntExtra(EXTRA_NOTIFY_INT_ID, 0))
        }
    }

    private fun renderStatus() {
        val storage = SecureStorage.getInstance(this)
        val lines = mutableListOf<String>()
        if (storage.isPaired) {
            lines += getString(
                R.string.main_paired,
                storage.deviceId ?: "?",
                storage.baseUrl ?: "?",
            )
        } else {
            lines += getString(R.string.main_not_paired)
        }
        val title = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_NOTIFICATION_BODY).orEmpty()
        if (title.isNotBlank() || body.isNotBlank()) {
            var last = getString(R.string.main_last_notification, title, body)
            val actionId = intent.getStringExtra(EXTRA_NOTIFICATION_ACTION_ID).orEmpty()
            if (actionId.isNotBlank()) last += " (action: $actionId)"
            lines += last
        }
        statusView.text = lines.joinToString("\n\n")
    }

    companion object {
        const val EXTRA_NOTIFICATION_TITLE = "pc_notification_title"
        const val EXTRA_NOTIFICATION_BODY = "pc_notification_body"
        /** Opaque backend notification id, if the payload carried one. */
        const val EXTRA_NOTIFICATION_ID = "pc_notification_id"
        /** Tapped action button id, or null for a content tap. */
        const val EXTRA_NOTIFICATION_ACTION_ID = "pc_notification_action_id"
        /** Int id to cancel on open (action taps don't auto-dismiss). */
        const val EXTRA_NOTIFY_INT_ID = "pc_notify_int_id"
    }
}
