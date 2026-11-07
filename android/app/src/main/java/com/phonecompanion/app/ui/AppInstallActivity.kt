package com.phonecompanion.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phonecompanion.app.R
import com.phonecompanion.app.notify.AppInstallCancels
import com.phonecompanion.app.notify.AppInstallCrypto
import com.phonecompanion.app.notify.AppInstallDownloader
import com.phonecompanion.app.notify.AppInstallFiles
import com.phonecompanion.app.notify.AppInstallInstaller
import com.phonecompanion.app.notify.AppInstallNotifications
import com.phonecompanion.app.notify.AppInstallPayload
import com.phonecompanion.app.notify.AppInstallTerminals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pre-install UI for a remote APK push.
 *
 * Opened ONLY by an explicit tap on the `Install <label>?` notification —
 * never automatically, never on boot/receive. Shows the package, version
 * (or 'unknown version'), short SHA-256, and source host so a swapped URL
 * is visible before anything is fetched.
 *
 * Unknown-sources onboarding: when `canRequestPackageInstalls()` is false
 * the Install button stays disabled behind a short explanation plus a
 * deep-link to this app's "Install unknown apps" settings page; the grant
 * is re-checked in [onResume], and install is never attempted without it.
 *
 * Flow (explicit Install tap only): HTTPS download with progress (shared
 * OkHttp stack, 100 MB cap, Cancel action) → SHA-256 verify BEFORE any
 * install attempt (mismatch = hard refuse, error notification naming the
 * package, file deleted) → PackageInstaller commit. The installer-result
 * receiver posts the single terminal notification for the install phase;
 * every download/verify terminal state posts exactly one notification (or
 * this in-app state) and never retries.
 *
 * Logging discipline: package names and push ids only — never APK URLs,
 * hashes, tokens, or credentials.
 */
class AppInstallActivity : AppCompatActivity() {

    private var payload: AppInstallPayload? = null
    private var downloading = false
    private var committed = false

    private lateinit var detailView: TextView
    private lateinit var packageView: TextView
    private lateinit var versionView: TextView
    private lateinit var shaView: TextView
    private lateinit var sourceView: TextView
    private lateinit var unknownSources: View
    private lateinit var unknownSourcesBody: TextView
    private lateinit var allowButton: Button
    private lateinit var installButton: Button
    private lateinit var cancelButton: Button
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_install)

        detailView = findViewById(R.id.appInstallTitle)
        packageView = findViewById(R.id.appInstallPackage)
        versionView = findViewById(R.id.appInstallVersion)
        shaView = findViewById(R.id.appInstallSha)
        sourceView = findViewById(R.id.appInstallSource)
        unknownSources = findViewById(R.id.appInstallUnknownSources)
        unknownSourcesBody = findViewById(R.id.appInstallUnknownSourcesBody)
        allowButton = findViewById(R.id.appInstallAllowButton)
        installButton = findViewById(R.id.appInstallButton)
        cancelButton = findViewById(R.id.appInstallCancelButton)
        statusView = findViewById(R.id.appInstallStatus)

        val parsed = AppInstallPayload.fromExtras(
            intent.extras?.let { bundle ->
                buildMap {
                    for (key in bundle.keySet()) {
                        put(key, bundle.getString(key))
                    }
                }
            } ?: emptyMap(),
        )
        if (parsed == null) {
            Log.w(TAG, "app_install opened with invalid extras; refusing")
            showInvalid()
            return
        }
        payload = parsed
        renderDetails(parsed)

        allowButton.setOnClickListener { openUnknownSourcesSettings() }
        installButton.setOnClickListener { startFlow(parsed) }
        cancelButton.setOnClickListener {
            if (downloading) {
                // Stay open: the flow observes the flag and terminates with
                // exactly one user-visible state.
                AppInstallCancels.requestCancel(parsed.pushId)
            } else {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        payload?.let { renderUnknownSources(it) }
    }

    override fun onDestroy() {
        // Abandoning the screen mid-download must not leak the transfer:
        // the loop observes the flag and terminates with its single state.
        payload?.let { if (downloading) AppInstallCancels.requestCancel(it.pushId) }
        super.onDestroy()
    }

    private fun showInvalid() {
        detailView.text = getString(R.string.app_install_title)
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.app_install_error_invalid)
        installButton.isEnabled = false
        unknownSources.visibility = View.GONE
        cancelButton.text = getString(R.string.app_install_close)
        cancelButton.setOnClickListener { finish() }
    }

    private fun renderDetails(p: AppInstallPayload) {
        detailView.text = "Install ${p.label}?"
        packageView.text = getString(R.string.app_install_package_label, p.packageName)
        versionView.text = getString(R.string.app_install_version_label, p.displayVersion())
        shaView.text = getString(R.string.app_install_sha_label, p.shortSha())
        sourceView.text = getString(R.string.app_install_source_label, p.apkUrl.hostPart())
        renderUnknownSources(p)
    }

    /**
     * Unknown-sources gate: without the grant the Install button is
     * disabled behind the explainer + Settings deep-link. Re-checked on
     * every resume (the user returns from Settings here).
     */
    private fun renderUnknownSources(p: AppInstallPayload) {
        val granted = packageManager.canRequestPackageInstalls()
        unknownSources.visibility = if (granted) View.GONE else View.VISIBLE
        installButton.isEnabled = granted && !downloading
        if (!granted) {
            unknownSourcesBody.text =
                getString(R.string.app_install_unknown_sources_body, p.label)
        }
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
    }

    private fun startFlow(p: AppInstallPayload) {
        // Re-check at tap time: the grant could have been revoked while the
        // screen was open. Never install without it.
        if (!packageManager.canRequestPackageInstalls()) {
            renderUnknownSources(p)
            return
        }
        if (downloading) return
        downloading = true
        // Drop any stale cancel flag from an earlier dead flow (the cancel
        // receiver never clears: the flow owner owns the flag lifecycle, so
        // a concurrent Cancel tap can never be lost to a clear here — this
        // runs strictly before the download loop starts observing).
        AppInstallCancels.clear(p.pushId)
        installButton.isEnabled = false
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.app_install_downloading, 0)

        lifecycleScope.launch {
            var terminal: (() -> Unit)? = null
            try {
                val dest = AppInstallFiles.apkFile(this@AppInstallActivity, p)
                val download = withContext(Dispatchers.IO) {
                    AppInstallDownloader.download(p, dest) { read, total ->
                        val pct = total?.takeIf { it > 0 }?.let { (read * 100 / it).toInt() }
                        runOnUiThread {
                            if (downloading) {
                                statusView.text = getString(
                                    R.string.app_install_downloading,
                                    pct ?: 0,
                                )
                            }
                        }
                        AppInstallNotifications.notifyProgress(this@AppInstallActivity, p, read, total)
                    }
                }
                when (download) {
                    is AppInstallDownloader.Result.Done -> {
                        AppInstallNotifications.notifyProgress(
                            this@AppInstallActivity,
                            p,
                            download.bytes,
                            download.bytes,
                        )
                        runOnUiThread { statusView.text = getString(R.string.app_install_verifying) }
                        val match = withContext(Dispatchers.IO) {
                            AppInstallCrypto.verifyFile(dest, p.sha256)
                        }
                        if (!match) {
                            runCatching { dest.delete() }
                            // Package only in logcat: never hashes.
                            Log.w(TAG, "app_install sha mismatch for ${p.packageName}: refusing")
                            if (claim(p)) {
                                AppInstallNotifications.notifyHashMismatch(
                                    this@AppInstallActivity,
                                    p.packageName,
                                    p.pushId,
                                )
                            }
                            terminal = {
                                statusView.text = getString(R.string.app_install_error_mismatch)
                            }
                            return@launch
                        }
                        runOnUiThread { statusView.text = getString(R.string.app_install_installing) }
                        val started = withContext(Dispatchers.IO) {
                            AppInstallInstaller.commit(this@AppInstallActivity, p, dest)
                        }
                        if (!started) {
                            Log.w(TAG, "app_install commit failed for ${p.packageName}")
                            if (claim(p)) {
                                AppInstallNotifications.notifyFailed(
                                    this@AppInstallActivity,
                                    p.packageName,
                                    p.label,
                                    p.pushId,
                                    null,
                                )
                            }
                            terminal = {
                                statusView.text = getString(R.string.app_install_error_install)
                            }
                            return@launch
                        }
                        // Install phase terminal state belongs to the
                        // result receiver (exactly one notification).
                        committed = true
                        terminal = { finish() }
                    }
                    is AppInstallDownloader.Result.TooLarge -> {
                        Log.w(TAG, "app_install over cap for ${p.packageName}")
                        if (claim(p)) {
                            AppInstallNotifications.notifyTooLarge(
                                this@AppInstallActivity,
                                p.packageName,
                                p.pushId,
                            )
                        }
                        terminal = {
                            statusView.text = getString(R.string.app_install_error_too_large)
                        }
                    }
                    is AppInstallDownloader.Result.Cancelled -> {
                        if (claim(p)) {
                            AppInstallNotifications.notifyDownloadCancelled(
                                this@AppInstallActivity,
                                p.packageName,
                                p.pushId,
                            )
                        }
                        terminal = {
                            statusView.text = getString(R.string.app_install_error_cancelled)
                        }
                    }
                    is AppInstallDownloader.Result.Failed -> {
                        Log.w(TAG, "app_install download failed for ${p.packageName}")
                        if (claim(p)) {
                            AppInstallNotifications.notifyDownloadFailed(
                                this@AppInstallActivity,
                                p.packageName,
                                p.pushId,
                            )
                        }
                        terminal = {
                            statusView.text = getString(R.string.app_install_error_download)
                        }
                    }
                }
            } finally {
                downloading = false
                AppInstallCancels.clear(p.pushId)
                // A destroyed flow (back press / destroy mid-download) that
                // never reached a terminal path still ends exactly once:
                // the claim only wins when nobody posted yet (claims are
                // TTL-managed — never cleared here, so the winner's claim
                // keeps the loser silent).
                if (!committed && terminal == null && !isFinishing) {
                    if (claim(p)) {
                        AppInstallNotifications.notifyDownloadCancelled(
                            this@AppInstallActivity,
                            p.packageName,
                            p.pushId,
                        )
                    }
                }
                runOnUiThread {
                    terminal?.invoke()
                    if (!committed && !isFinishing) renderUnknownSources(p)
                }
            }
        }
    }

    /** Claims the single terminal post for this flow (see [AppInstallTerminals]). */
    private fun claim(p: AppInstallPayload): Boolean =
        AppInstallTerminals.claimTerminal(p.pushId)

    companion object {
        private const val TAG = "AppInstallActivity"
    }
}

/** Host part of an https URL for the pre-install "Source" line. */
private fun String.hostPart(): String =
    substringAfter("://").substringBefore("/").ifEmpty { this }
