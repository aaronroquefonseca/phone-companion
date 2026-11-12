package com.phonecompanion.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonecompanion.app.R
import com.phonecompanion.app.notify.AppInstallEvents
import com.phonecompanion.app.notify.AppInstallFiles
import com.phonecompanion.app.notify.AppInstallPayload
import com.phonecompanion.app.notify.AppInstallProgress
import com.phonecompanion.app.notify.AppInstallVisibility
import com.phonecompanion.app.notify.ShizukuPrivilegedInstall
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Pre-install UI for a remote APK push: optional detail, never a gate.
 *
 * Opened ONLY by an explicit tap (progress/grant notification, or the
 * foreground dialog's Details action) — never automatically, never on
 * boot/receive. Shows the package, version (or 'unknown version'), short
 * SHA-256, and source host so a swapped URL stays visible while the
 * (already running) transfer proceeds.
 *
 * The transfer starts on receipt, not here: by the time this screen
 * opens, the download is in flight or a verified APK is already waiting
 * for the grant. Unknown-sources onboarding: when `canRequestPackageInstalls()` is false
 * the Install button stays disabled behind a short explanation plus a
 * deep-link to this app's "Install unknown apps" settings page; the grant
 * is re-checked in [onResume], and install is never attempted without it
 * (the flow re-checks again after verify, before commit), EXCEPT on the
 * Shizuku silent path (privileged sessions need no grant — the flow skips
 * the gate only there, and only when already granted at commit time).
 * When Shizuku is reachable but ungranted, this screen shows the one-time
 * enable affordance (status line + request button, foreground-only
 * request, ask once then never nag). The Install tap
 * resumes from a verified cache when one waits (no re-fetch) and
 * otherwise downloads first — then the system's own Install confirmation
 * is the user's single press (or no press at all on the silent path).
 *
 * Flow: HTTPS download with progress (shared OkHttp stack, 100 MB cap,
 * Cancel action) → SHA-256 verify BEFORE any install attempt (mismatch
 * = hard refuse, error notification naming the package, file deleted) →
 * PackageInstaller commit. The installer-result receiver posts the
 * single terminal notification for the install phase; every
 * download/verify terminal state posts exactly one notification (or this
 * in-app state) and never retries.
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
    private lateinit var shizukuBox: View
    private lateinit var shizukuButton: Button
    private lateinit var installButton: Button
    private lateinit var cancelButton: Button
    private lateinit var statusView: TextView

    /**
     * Shizuku permission result: re-render so a fresh grant hides the
     * enable affordance (and a denial hides it too — ask once, never
     * nag). The request itself only ever fires from this foreground
     * screen ([requestShizukuPermission]).
     */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                runOnUiThread { payload?.let { render(vm.uiState.value) } }
            }
        }

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
        shizukuBox = findViewById(R.id.appInstallShizuku)
        shizukuButton = findViewById(R.id.appInstallShizukuButton)
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
        // sits next to (or is mistaken for) this push — cache and the
        // staged dir alike.
        AppInstallFiles.purgeStaleCache(this, parsed.pushId)
        AppInstallFiles.purgeStaleStaged(this, parsed.pushId)
        payload = parsed
        renderDetails(parsed)
        render(vm.uiState.value)

        allowButton.setOnClickListener { openUnknownSourcesSettings() }
        shizukuButton.setOnClickListener { requestShizukuPermission() }
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
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
                launch {
                    vm.uiState.collect { render(it) }
                }
                // The receipt-triggered auto-download runs detached from
                // this screen's ViewModel; its registry is a StateFlow so
                // the in-app percentage tracks it live instead of freezing
                // at whatever value was present on the first render.
                launch {
                    AppInstallProgress.entries.collect {
                        render(vm.uiState.value)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may return from the Settings grant screen (or grant
        // Shizuku in its manager app directly): re-check both gates and
        // re-render so a revoked grant disables Install and a fresh
        // Shizuku grant hides the enable affordance.
        payload?.let { render(vm.uiState.value) }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
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
        // A receipt-triggered auto-download for this push runs detached from
        // this screen's ViewModel; its progress lives in the registry. When
        // this screen is idle (Ready) but a download is in flight, surface it
        // here so the user always sees progress — even when the status-bar
        // notification cannot post.
        val activeDownload = AppInstallProgress.get(p.pushId)
        if (state is AppInstallViewModel.InstallUiState.Ready && activeDownload != null) {
            statusView.visibility = View.VISIBLE
            statusView.text = getString(R.string.app_install_downloading, activeDownload.percent ?: 0)
            if (!AppInstallVisibility.canPostNotifications(this)) {
                statusView.append("\n" + getString(R.string.app_install_notifications_off))
            }
            installButton.isEnabled = false
            renderUnknownSources(p)
            renderShizuku()
            return
        }
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
            is AppInstallViewModel.InstallUiState.SilentInstalling -> {
                statusView.visibility = View.VISIBLE
                statusView.text = getString(R.string.app_install_silent_installing)
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
        renderShizuku()
    }

    private fun showInvalid() {
        detailView.text = getString(R.string.app_install_title)
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.app_install_error_invalid)
        installButton.isEnabled = false
        unknownSources.visibility = View.GONE
        shizukuBox.visibility = View.GONE
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

    /**
     * Shizuku silent-install affordance: shown only while the binder is
     * alive, permission is missing, and the user has not denied before
     * (Shizuku rationale false). After any denial — or when Shizuku is
     * absent/dead/already granted — the box hides and the dialog flow
     * proceeds silently (ask once, never nag). The request fires only from
     * this foreground screen, never from background context.
     */
    private fun renderShizuku() {
        val offer = runCatching { ShizukuPrivilegedInstall.shouldOfferRequest() }.getOrDefault(false)
        shizukuBox.visibility = if (offer) View.VISIBLE else View.GONE
    }

    private fun requestShizukuPermission() {
        // Foreground only (this screen): a dead binder throws — swallow
        // and re-render on resume instead of crashing.
        runCatching { ShizukuPrivilegedInstall.requestPermission(SHIZUKU_REQUEST_CODE) }
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

        /**
         * Foreground-only Shizuku permission request code (delivered via
         * the Shizuku result listener, not Activity result APIs).
         */
        private const val SHIZUKU_REQUEST_CODE = 5001

        /** Explicit detail intent for a validated payload (dialog/notification taps). */
        fun detailIntent(context: Context, payload: AppInstallPayload): Intent =
            Intent(context, AppInstallActivity::class.java).apply {
                for ((k, v) in payload.toExtras()) putExtra(k, v)
            }

        /**
         * Foreground dialog for a fresh command while the app is open (the
         * status-bar notification alone is easy to miss with the app
         * foregrounded). Names the app label with at most two actions:
         * Details opens this screen, Dismiss only dismisses — the
         * auto-download proceeds regardless and cancellation stays with
         * the progress Cancel action. Call only when resumed (the caller
         * gates on its lifecycle); never throws.
         */
        fun showAvailable(activity: AppCompatActivity, payload: AppInstallPayload) {
            if (activity.isFinishing || activity.isDestroyed) return
            runCatching {
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.app_install_foreground_title, payload.label))
                    .setMessage(activity.getString(R.string.app_install_foreground_body, payload.label))
                    .setPositiveButton(R.string.app_install_details) { _, _ ->
                        runCatching { activity.startActivity(detailIntent(activity, payload)) }
                    }
                    .setNegativeButton(R.string.app_install_dismiss, null)
                    .show()
            }
        }
    }
}

/** Host part of an https URL for the pre-install "Source" line. */
private fun String.hostPart(): String =
    substringAfter("://").substringBefore("/").ifEmpty { this }
