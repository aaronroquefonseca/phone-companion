package com.phonecompanion.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonecompanion.app.R
import com.phonecompanion.app.notify.AppInstallFiles
import com.phonecompanion.app.notify.AppInstallPayload
import kotlinx.coroutines.launch

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
 * is re-checked in [onResume], and install is never attempted without it
 * (the flow re-checks again after verify, before commit).
 *
 * Flow (explicit Install tap only): HTTPS download with progress (shared
 * OkHttp stack, 100 MB cap, Cancel action) → SHA-256 verify BEFORE any
 * install attempt (mismatch = hard refuse, error notification naming the
 * package, file deleted) → PackageInstaller commit. The installer-result
 * receiver posts the single terminal notification for the install phase;
 * every download/verify terminal state posts exactly one notification (or
 * this in-app state) and never retries.
 *
 * Rotation-safe: the transfer lives in [AppInstallViewModel] (retained
 * across config changes), so rotation neither cancels the download nor
 * spawns a second flow — the recreated Activity just re-collects the
 * state. Only a real finish (back press / explicit close) cancels.
 *
 * Logging discipline: package names and push ids only — never APK URLs,
 * hashes, tokens, or credentials.
 */
class AppInstallActivity : AppCompatActivity() {

    private val vm: AppInstallViewModel by viewModels()

    private var payload: AppInstallPayload? = null

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
        // Drop superseded/abandoned APKs on entry so a stale file never
        // sits next to (or is mistaken for) this push.
        AppInstallFiles.purgeStaleCache(this, parsed.pushId)
        payload = parsed
        renderDetails(parsed)
        render(vm.uiState.value)

        allowButton.setOnClickListener { openUnknownSourcesSettings() }
        installButton.setOnClickListener { vm.start(parsed) }
        cancelButton.setOnClickListener {
            if (vm.isFlowActive) {
                // Stay open: the flow observes the flag and terminates
                // with exactly one user-visible state via the funnel.
                vm.cancel()
            } else {
                finish()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may return from the Settings grant screen: re-check
        // the gate (and re-render so a revoked grant disables Install).
        payload?.let { render(vm.uiState.value) }
    }

    override fun onDestroy() {
        // Real finish only (back press / explicit close): abandoning the
        // screen mid-download cancels the transfer, which funnels its
        // single terminal state. Config changes (rotation) must NOT
        // cancel — the ViewModel retains the flow across them.
        if (isFinishing) vm.cancel()
        super.onDestroy()
    }

    /** Single render exit: one state → one screen, no flag agreement. */
    private fun render(state: AppInstallViewModel.InstallUiState) {
        val p = payload ?: return
        when (state) {
            is AppInstallViewModel.InstallUiState.Ready -> {
                statusView.visibility = View.GONE
                installButton.isEnabled = true
            }
            is AppInstallViewModel.InstallUiState.Downloading -> {
                statusView.visibility = View.VISIBLE
                statusView.text = getString(R.string.app_install_downloading, state.percent ?: 0)
                installButton.isEnabled = false
            }
            is AppInstallViewModel.InstallUiState.Verifying -> {
                statusView.visibility = View.VISIBLE
                statusView.text = getString(R.string.app_install_verifying)
                installButton.isEnabled = false
            }
            is AppInstallViewModel.InstallUiState.Installing -> {
                statusView.visibility = View.VISIBLE
                statusView.text = getString(R.string.app_install_installing)
                installButton.isEnabled = false
            }
            is AppInstallViewModel.InstallUiState.Finished -> {
                statusView.visibility = View.VISIBLE
                statusView.text = state.message
                // Retry is a fresh tap (fresh attempt nonce, fresh claim).
                installButton.isEnabled = true
            }
            is AppInstallViewModel.InstallUiState.HandedToInstaller -> {
                finish()
                return
            }
        }
        renderUnknownSources(p)
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
        if (!granted) {
            installButton.isEnabled = false
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

    companion object {
        private const val TAG = "AppInstallActivity"
    }
}

/** Host part of an https URL for the pre-install "Source" line. */
private fun String.hostPart(): String =
    substringAfter("://").substringBefore("/").ifEmpty { this }
