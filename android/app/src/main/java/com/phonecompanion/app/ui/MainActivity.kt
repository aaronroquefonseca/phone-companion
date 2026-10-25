package com.phonecompanion.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phonecompanion.app.BuildConfig
import com.phonecompanion.app.R
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.work.SharedPrefsPollStateStore
import com.phonecompanion.app.work.WidgetPoller
import kotlinx.coroutines.launch

/**
 * Launcher screen: pairing status, navigation to [PairActivity] and
 * [WidgetsActivity].
 *
 * The POST_NOTIFICATIONS runtime permission is requested only once this
 * phone is paired — never on the unpaired launcher. After a successful
 * pairing the just-paired confirmation offers it with a one-line rationale;
 * after a denial the prompt becomes a persistent line with a Settings
 * deep-link instead of re-prompting (see [NotificationPermissionPolicy]).
 *
 * The overflow menu holds Unpair (confirm → clear credentials, drop the
 * widget cache, push fail-closed widget states, refresh status),
 * Notification settings, and an About explainer (channels, widget slots,
 * masked secrets, docs pointers).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var justPairedCard: LinearLayout
    private lateinit var justPairedBody: TextView
    private lateinit var notifPrompt: LinearLayout
    private lateinit var notifPromptText: TextView
    private lateinit var notifAllowButton: Button
    private lateinit var notifSettingsButton: Button
    private lateinit var secretRefreshNote: TextView

    private lateinit var denialTracker: NotificationDenialTracker
    private var consumedNotificationExtras = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            denialTracker.onPermissionResult(granted)
            renderStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        denialTracker = NotificationDenialTracker(
            readPersisted = {
                getSharedPreferences(PREFS_NOTIF, MODE_PRIVATE)
                    .getBoolean(KEY_DENIED_BEFORE, false)
            },
            writePersisted = { denied ->
                getSharedPreferences(PREFS_NOTIF, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DENIED_BEFORE, denied)
                    .apply()
            },
        )

        statusView = findViewById(R.id.pairStatus)
        justPairedCard = findViewById(R.id.justPairedCard)
        justPairedBody = findViewById(R.id.justPairedBody)
        notifPrompt = findViewById(R.id.notifPrompt)
        notifPromptText = findViewById(R.id.notifPromptText)
        notifAllowButton = findViewById(R.id.notifAllowButton)
        notifSettingsButton = findViewById(R.id.notifSettingsButton)
        secretRefreshNote = findViewById(R.id.secretRefreshNote)

        findViewById<Button>(R.id.pairButton).setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }
        findViewById<Button>(R.id.widgetsButton).setOnClickListener {
            startActivity(Intent(this, WidgetsActivity::class.java))
        }
        findViewById<Button>(R.id.continueButton).setOnClickListener {
            intent.removeExtra(EXTRA_JUST_PAIRED)
            renderStatus()
        }
        notifAllowButton.setOnClickListener { requestNotifications() }
        notifSettingsButton.setOnClickListener { openNotificationSettings() }

        statusView.setOnLongClickListener {
            copyPairedLine()
            true
        }

        if (savedInstanceState == null) {
            dismissFromIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // No notification state to save: the denial flag lives in
        // SharedPreferences (see denialTracker) and survives process death.
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumedNotificationExtras = false
        dismissFromIntent(intent)
        renderStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val paired = SecureStorage.getInstance(this).isPaired
        menu.findItem(R.id.menu_unpair)?.isVisible = paired
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_unpair -> {
            confirmUnpair()
            true
        }
        R.id.menu_notification_settings -> {
            openNotificationSettings()
            true
        }
        R.id.menu_about -> {
            showAbout()
            true
        }
        else -> super.onOptionsItemSelected(item)
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
        val paired = storage.isPaired

        if (paired) {
            val name = storage.deviceName ?: getString(R.string.main_not_paired)
            statusView.text = getString(R.string.main_paired, name, storage.baseUrl ?: "?")
        } else {
            // Revoked reads differently from never-paired: the wipe marker
            // (plain pref, survives the credential wipe) tells them apart.
            val revokedAtMs = runCatching {
                SharedPrefsPollStateStore(this).revokedAtMs
            }.getOrNull()
            statusView.text = RevocationCopy.mainUnpairedText(
                revokedAtMs,
                getString(R.string.main_not_paired),
                getString(R.string.main_revoked),
            )
        }

        renderJustPaired(paired)
        renderNotificationPrompt(paired)
        renderNotificationExtras()
        secretRefreshNote.visibility = if (paired) View.VISIBLE else View.GONE
    }

    /**
     * Human pair-success confirmation: device name + server, never the raw
     * device id. Shown only for the navigation that carries
     * [EXTRA_JUST_PAIRED]; an explicit Continue dismisses it.
     */
    private fun renderJustPaired(paired: Boolean) {
        if (paired && intent.getBooleanExtra(EXTRA_JUST_PAIRED, false)) {
            val name = intent.getStringExtra(EXTRA_JUST_PAIRED_NAME)
                ?: SecureStorage.getInstance(this).deviceName
                ?: "?"
            val server = intent.getStringExtra(EXTRA_JUST_PAIRED_SERVER)
                ?: SecureStorage.getInstance(this).baseUrl
                ?: "?"
            justPairedBody.text = getString(R.string.main_just_paired_body, name, server)
            justPairedCard.visibility = View.VISIBLE
        } else {
            justPairedCard.visibility = View.GONE
        }
    }

    private fun renderNotificationPrompt(paired: Boolean) {
        val granted = notificationsGranted()
        val shouldRationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            false
        }
        when (NotificationPermissionPolicy.decide(paired, granted, denialTracker.deniedBefore, shouldRationale)) {
            NotificationPermissionPolicy.PromptState.RATIONALE -> {
                notifPrompt.visibility = View.VISIBLE
                notifPromptText.text = getString(R.string.notif_rationale)
                notifAllowButton.visibility = View.VISIBLE
                notifSettingsButton.visibility = View.GONE
            }
            NotificationPermissionPolicy.PromptState.DENIED -> {
                notifPrompt.visibility = View.VISIBLE
                notifPromptText.text = getString(R.string.notif_denied)
                notifAllowButton.visibility = View.GONE
                notifSettingsButton.visibility = View.VISIBLE
            }
            NotificationPermissionPolicy.PromptState.GRANTED,
            NotificationPermissionPolicy.PromptState.HIDDEN,
            -> notifPrompt.visibility = View.GONE
        }
    }

    /**
     * Human title/body only: the raw backend notification id and the raw
     * action id are never shown (an action tap gets a generic line instead
     * of the opaque id). Extras are consumed after the first render so
     * rotation and re-resume never duplicate the line.
     */
    private fun renderNotificationExtras() {
        if (consumedNotificationExtras) return
        consumedNotificationExtras = true
        val title = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_NOTIFICATION_BODY).orEmpty()
        if (title.isBlank() && body.isBlank()) return
        var last = getString(R.string.main_last_notification, title, body)
        if (intent.getStringExtra(EXTRA_NOTIFICATION_ACTION_ID).orEmpty().isNotBlank()) {
            last += "\n" + getString(R.string.main_last_notification_action)
        }
        statusView.append("\n\n" + last)
        // Consume: rotation and onResume must not duplicate the line.
        intent.removeExtra(EXTRA_NOTIFICATION_TITLE)
        intent.removeExtra(EXTRA_NOTIFICATION_BODY)
        intent.removeExtra(EXTRA_NOTIFICATION_ID)
        intent.removeExtra(EXTRA_NOTIFICATION_ACTION_ID)
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", packageName, null))
        }
        runCatching { startActivity(intent) }
    }

    private fun copyPairedLine() {
        val text = statusView.text.toString()
        if (text.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pairing", text))
        Toast.makeText(this, R.string.main_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_title)
            .setMessage(R.string.unpair_message)
            .setPositiveButton(R.string.unpair_confirm) { _, _ -> unpair() }
            .setNegativeButton(R.string.unpair_cancel, null)
            .show()
    }

    /**
     * Explicit unpair: clear credentials, drop the widget cache, clear the
     * revocation marker (a fresh unpair is neither revoked nor paired —
     * the next render shows the generic never-paired copy), push the
     * fail-closed widget states, and refresh this screen. All APIs already
     * exist ([SecureStorage.clearCredentials], [WidgetPoller.updateAllWidgets]).
     */
    private fun unpair() {
        lifecycleScope.launch {
            SecureStorage.getInstance(this@MainActivity).clearCredentials()
            runCatching {
                SharedPrefsPollStateStore(this@MainActivity).revokedAtMs = null
            }
            runCatching {
                DatabaseHolder.get(this@MainActivity).widgetCacheDao().clearAll()
            }
            runCatching {
                WidgetPoller.updateAllWidgets(this@MainActivity)
            }
            intent.removeExtra(EXTRA_JUST_PAIRED)
            renderStatus()
            invalidateOptionsMenu()
            Toast.makeText(this@MainActivity, R.string.unpaired_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAbout() {
        val body = getString(R.string.about_body) + "\n\n" +
            AppVersion.line(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA)
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        /** Set by PairActivity on the success navigation (finding 6). */
        const val EXTRA_JUST_PAIRED = "pc_just_paired"
        /** Human device name sent with enroll (never the raw device id). */
        const val EXTRA_JUST_PAIRED_NAME = "pc_just_paired_name"
        /** Normalized server URL the device paired with. */
        const val EXTRA_JUST_PAIRED_SERVER = "pc_just_paired_server"

        private const val PREFS_NOTIF = "pc_notif_prompts"
        private const val KEY_DENIED_BEFORE = "notif_denied_before"
    }
}
