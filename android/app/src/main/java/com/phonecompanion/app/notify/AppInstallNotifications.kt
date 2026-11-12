package com.phonecompanion.app.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.phonecompanion.app.R
import com.phonecompanion.app.ui.AppInstallActivity

/**
 * User-visible notifications for the remote APK install flow.
 *
 * Auto-download story: a fresh command immediately starts its download
 * and the FIRST post is the progress notification (same id throughout,
 * so progress replaces nothing and every later state replaces it); there
 * is no "tap to download" prompt. Tapping progress opens the pre-install
 * UI as optional detail — it never gates the transfer. All posts go to
 * the shared general channel ([NotificationChannels]: no new channel —
 * the registry pattern only mints per-kind channels for generic kinds,
 * and `app_install` is a dedicated flow, not a generic kind). Every
 * terminal state posts exactly one notification (or in-app state),
 * claimed per Install-tap attempt via [AppInstallSessions] so a retried
 * system broadcast or a racing receiver can never double-post. Nothing
 * here retries.
 *
 * Install-failure notifications are cause-aware ([AppInstallResult.failureKindOf]):
 * a signature/update-incompatible failure is deterministic (retrying the
 * same bytes can never succeed), so it explains in plain words and offers
 * the update target's system App-info screen (where Uninstall lives) with
 * NO Retry; any other commit/install failure keeps the existing copy and
 * adds a Retry action that re-opens the pre-install UI, whose Install tap
 * resumes via the runResume path (verified cache reused, re-download only
 * on a miss). The raw installer message rides along as secondary expanded
 * text only — never the primary explanation. User-cancelled,
 * hash-mismatch, blocked, and too-large posts stay exactly as they were
 * (no actions).
 *
 * Logging discipline: package names and push ids only — never APK URLs,
 * hashes, tokens, or credentials.
 */
object AppInstallNotifications {

    const val ACTION_CANCEL_DOWNLOAD = "pc_app_install_cancel"

    /** Stable action ids for the failure-notification buttons (request codes). */
    const val ACTION_RETRY = "pc_app_install_retry"
    const val ACTION_APP_INFO = "pc_app_install_app_info"

    /** Detail text is truncated to this many chars (operator-influenced). */
    const val MAX_DETAIL_CHARS = 200

    /**
     * Terminal target: the push-scoped identifiers every terminal post
     * needs. Receivers (which never see the full payload) build this from
     * their intent extras; the activity builds it from the payload.
     */
    data class TerminalTarget(
        val pushId: String,
        val packageName: String,
        val label: String?,
    ) {
        companion object {
            fun from(payload: AppInstallPayload) = TerminalTarget(
                pushId = payload.pushId,
                packageName = payload.packageName,
                label = payload.label,
            )
        }
    }

    /**
     * Tap target: opens the pre-install UI as optional detail (progress,
     * grant onboarding, retry). Never auto-installs: the transfer runs on
     * receipt and the Install tap inside the activity only resumes or
     * commits it.
     */
    fun detailIntent(context: Context, payload: AppInstallPayload): PendingIntent {
        return PendingIntent.getActivity(
            context,
            CompanionMessagingService.tapRequestCode(payload.notifyId()),
            AppInstallActivity.detailIntent(context, payload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Progress post (the flow's first post: no prompt precedes it):
     * determinate when the server sent a usable Content-Length,
     * indeterminate otherwise. Tap opens the pre-install UI as optional
     * detail (it never gates the transfer). Carries the Cancel action
     * that aborts the download and cleans up. The Cancel intent carries
     * the per-tap [attempt] nonce so the receiver's terminal claim is
     * scoped to this attempt.
     */
    fun notifyProgress(
        context: Context,
        payload: AppInstallPayload,
        attempt: String?,
        bytesRead: Long,
        totalBytes: Long?,
    ) {
        val manager = context.notificationManager()
        NotificationChannels.ensureInstalls(manager)
        val cancel = PendingIntent.getBroadcast(
            context,
            CompanionMessagingService.actionRequestCode(payload.notifyId(), ACTION_CANCEL_DOWNLOAD),
            Intent(context, AppInstallCancelReceiver::class.java)
                .setAction(ACTION_CANCEL_DOWNLOAD)
                .putExtra(AppInstallPayload.EXTRA_PUSH_ID, payload.pushId)
                .putExtra(AppInstallPayload.EXTRA_PACKAGE, payload.packageName)
                .putExtra(AppInstallPayload.EXTRA_LABEL, payload.label)
                .putExtra(AppInstallPayload.EXTRA_ATTEMPT, attempt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val determinate = totalBytes != null && totalBytes > 0
        manager.notify(
            payload.notifyId(),
            NotificationCompat.Builder(context, NotificationChannels.INSTALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_install_progress_title, payload.label))
                .setContentText("${payload.packageName} • ${payload.displayVersion()}")
                .setProgress(
                    if (determinate) totalBytes!!.toInt().coerceAtLeast(1) else 0,
                    bytesRead.toInt().coerceAtLeast(0),
                    !determinate,
                )
                .setContentIntent(detailIntent(context, payload))
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addAction(0, context.getString(R.string.app_install_cancel), cancel)
                .build(),
        )
    }

    /**
     * Grant gate after a verified auto-download: the unknown-sources
     * grant was missing at commit time, so nothing committed and the
     * verified APK stays cached. Tap opens the pre-install UI (the
     * existing grant/onboarding flow); after the one-time grant the
     * activity's Install tap resumes from the cache without re-fetching.
     * Claimed per attempt like every terminal post, so a racing path
     * stays silent.
     */
    fun notifyGrant(
        context: Context,
        payload: AppInstallPayload,
        attempt: String?,
    ): Boolean {
        if (!AppInstallSessions.claimTerminal(payload.pushId, attempt)) return false
        val manager = context.notificationManager()
        NotificationChannels.ensureInstalls(manager)
        val display = payload.label
        manager.notify(
            payload.notifyId(),
            NotificationCompat.Builder(context, NotificationChannels.INSTALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_install_grant_title, display))
                .setContentText(context.getString(R.string.app_install_grant_body, display))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.app_install_grant_body, display)),
                )
                .setContentIntent(detailIntent(context, payload))
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    /**
     * Informational post after the ACTION_VIEW commit-failure fallback
     * ([AppInstallFallback]) opened the system installer: the VIEW intent
     * returns no result, so this note — not a FAILED terminal — is the
     * attempt's single post, telling the user to complete the install in
     * the system screen. The verified APK stays cached until the 24 h
     * stale purge (the install may complete minutes later; no installer
     * result ever returns to clean it). Claimed per attempt like every
     * terminal post, so a racing path stays silent. No actions: nothing
     * retries — the system installer owns the install now.
     */
    fun notifyFallbackOpened(
        context: Context,
        target: TerminalTarget,
        attempt: String?,
    ): Boolean {
        if (!AppInstallSessions.claimTerminal(target.pushId, attempt)) return false
        val display = target.label ?: target.packageName
        postTerminal(
            context,
            target.pushId,
            context.getString(R.string.app_install_fallback_title, display),
            context.getString(R.string.app_install_fallback_body, display),
            context.getString(R.string.app_install_fallback_body, display),
            contentIntent = null,
            actions = emptyList(),
        )
        return true
    }

    /**
     * Single terminal funnel: claims ([AppInstallSessions.claimTerminal])
     * and posts exactly one notification for the attempt. Every terminal
     * path in the flow (activity exit, cancel receiver, result receiver)
     * funnels through here with a consistent (kind, target, attempt,
     * detail?) order. Returns true when this caller won the claim and
     * posted; losers stay silent.
     *
     * @param payload when non-null and [kind] is FAILED, builds the Retry
     * tap target (the pre-install UI, whose Install tap resumes via the
     * runResume path). Receivers rebuild it from their intent extras; the
     * activity and auto-download pass what they already hold. A null
     * payload (or a non-FAILED kind) posts action-less, as before.
     */
    fun notifyTerminal(
        context: Context,
        kind: AppInstallFlow.TerminalKind,
        target: TerminalTarget,
        attempt: String?,
        detail: String? = null,
        payload: AppInstallPayload? = null,
    ): Boolean {
        if (!AppInstallSessions.claimTerminal(target.pushId, attempt)) return false
        val display = target.label ?: target.packageName
        val (title, content, expanded) = when (kind) {
            AppInstallFlow.TerminalKind.SUCCESS ->
                Triple(
                    context.getString(R.string.app_install_success_title, display),
                    context.getString(R.string.app_install_success_body, display),
                    context.getString(R.string.app_install_success_body, display),
                )
            AppInstallFlow.TerminalKind.USER_CANCELLED ->
                Triple(
                    context.getString(R.string.app_install_user_cancelled_title),
                    context.getString(R.string.app_install_user_cancelled_body, display),
                    context.getString(R.string.app_install_user_cancelled_body, display),
                )
            AppInstallFlow.TerminalKind.FAILED ->
                failureCopy(context, display, detail)
            // Hard refuse naming the package; the file is deleted by the caller.
            AppInstallFlow.TerminalKind.HASH_MISMATCH ->
                Triple(
                    context.getString(R.string.app_install_blocked_title),
                    context.getString(R.string.app_install_blocked_body, target.packageName),
                    context.getString(R.string.app_install_blocked_body, target.packageName),
                )
            AppInstallFlow.TerminalKind.DOWNLOAD_FAILED ->
                Triple(
                    context.getString(R.string.app_install_download_failed_title),
                    context.getString(R.string.app_install_download_failed_body, target.packageName),
                    context.getString(R.string.app_install_download_failed_body, target.packageName),
                )
            AppInstallFlow.TerminalKind.TOO_LARGE ->
                Triple(
                    context.getString(R.string.app_install_too_large_title),
                    context.getString(R.string.app_install_too_large_body, target.packageName),
                    context.getString(R.string.app_install_too_large_body, target.packageName),
                )
            AppInstallFlow.TerminalKind.DOWNLOAD_CANCELLED ->
                Triple(
                    context.getString(R.string.app_install_cancelled_title),
                    context.getString(R.string.app_install_cancelled_body, target.packageName),
                    context.getString(R.string.app_install_cancelled_body, target.packageName),
                )
        }
        val contentIntent =
            if (kind == AppInstallFlow.TerminalKind.FAILED) {
                payload?.let { retryPendingIntent(context, it) }
            } else {
                null
            }
        postTerminal(
            context,
            target.pushId,
            title,
            content,
            expanded,
            contentIntent,
            failureActions(context, target, payload, detail).takeIf {
                kind == AppInstallFlow.TerminalKind.FAILED
            }.orEmpty(),
        )
        return true
    }

    /**
     * Cause-aware copy for an install failure: plain-words primary
     * explanation plus the raw installer message as secondary expanded
     * text (transparency for bug reports — never the primary). Signature
     * mismatch names the label and the uninstall-first recovery; transient
     * failures keep the long-standing copy.
     */
    private fun failureCopy(
        context: Context,
        display: String,
        detail: String?,
    ): Triple<String, String, String> {
        val raw = detail?.take(MAX_DETAIL_CHARS)?.ifBlank { null }
        val (title, primary) = when (AppInstallResult.failureKindOf(raw)) {
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH ->
                context.getString(R.string.app_install_signature_title, display) to
                    context.getString(R.string.app_install_signature_body, display)
            AppInstallResult.FailureKind.TRANSIENT ->
                context.getString(R.string.app_install_failed_title) to
                    context.getString(R.string.app_install_failed_body_plain, display)
        }
        val expanded = if (raw != null) {
            context.getString(R.string.app_install_failed_expanded_body, primary, raw)
        } else {
            primary
        }
        return Triple(title, primary, expanded)
    }

    /**
     * Action buttons for an install failure ([AppInstallResult.failureActions]):
     * signature mismatch offers the update target's system App-info screen
     * (where Uninstall lives) with NO Retry; transient failures offer
     * Retry (the pre-install UI → runResume path) when the full payload is
     * available to build its tap target — without it the post stays
     * action-less rather than pointing nowhere.
     */
    private fun failureActions(
        context: Context,
        target: TerminalTarget,
        payload: AppInstallPayload?,
        detail: String?,
    ): List<Pair<String, PendingIntent>> {
        val raw = detail?.take(MAX_DETAIL_CHARS)?.ifBlank { null }
        return when (AppInstallResult.failureKindOf(raw)) {
            AppInstallResult.FailureKind.SIGNATURE_MISMATCH -> listOf(
                context.getString(R.string.app_install_app_info) to
                    appInfoPendingIntent(context, target),
            )
            AppInstallResult.FailureKind.TRANSIENT -> {
                val retry = payload?.let { retryPendingIntent(context, it) }
                    ?: return emptyList()
                listOf(context.getString(R.string.app_install_retry) to retry)
            }
        }
    }

    /**
     * Retry tap target: the pre-install UI as explicit action. Its Install
     * tap resumes via the runResume path — a still-intact verified APK
     * commits without re-fetching, otherwise the full download runs.
     */
    private fun retryPendingIntent(context: Context, payload: AppInstallPayload): PendingIntent {
        return PendingIntent.getActivity(
            context,
            CompanionMessagingService.actionRequestCode(payload.notifyId(), ACTION_RETRY),
            AppInstallActivity.detailIntent(context, payload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * App-info tap target: the update target's system App-info screen,
     * where Uninstall lives. The package is installed (that is why an
     * update-incompatible failure exists), so the screen resolves; if it
     * was removed since, the tap harmlessly shows "not installed".
     */
    private fun appInfoPendingIntent(context: Context, target: TerminalTarget): PendingIntent {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${target.packageName}"),
        )
        return PendingIntent.getActivity(
            context,
            CompanionMessagingService.actionRequestCode(
                AppInstallPayload.notifyIdFor(target.pushId),
                ACTION_APP_INFO,
            ),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun postTerminal(
        context: Context,
        pushId: String,
        title: String,
        content: String,
        expanded: String,
        contentIntent: PendingIntent?,
        actions: List<Pair<String, PendingIntent>>,
    ) {
        val manager = context.notificationManager()
        NotificationChannels.ensureInstalls(manager)
        // The single notifyId owner is the payload ([AppInstallPayload.notifyIdFor]):
        // the terminal replaces the progress rather than stacking.
        val builder = NotificationCompat.Builder(context, NotificationChannels.INSTALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setAutoCancel(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        for ((label, intent) in actions) builder.addAction(0, label, intent)
        manager.notify(AppInstallPayload.notifyIdFor(pushId), builder.build())
    }

    private fun Context.notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
