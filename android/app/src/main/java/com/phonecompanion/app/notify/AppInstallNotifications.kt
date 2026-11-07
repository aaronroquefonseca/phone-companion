package com.phonecompanion.app.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * Logging discipline: package names and push ids only — never APK URLs,
 * hashes, tokens, or credentials.
 */
object AppInstallNotifications {

    const val ACTION_CANCEL_DOWNLOAD = "pc_app_install_cancel"

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
        NotificationChannels.ensureGeneral(manager)
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
            NotificationCompat.Builder(context, NotificationChannels.GENERAL_CHANNEL_ID)
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
        NotificationChannels.ensureGeneral(manager)
        val display = payload.label
        manager.notify(
            payload.notifyId(),
            NotificationCompat.Builder(context, NotificationChannels.GENERAL_CHANNEL_ID)
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
     * Single terminal funnel: claims ([AppInstallSessions.claimTerminal])
     * and posts exactly one notification for the attempt. Every terminal
     * path in the flow (activity exit, cancel receiver, result receiver)
     * funnels through here with a consistent (kind, target, attempt,
     * detail?) order. Returns true when this caller won the claim and
     * posted; losers stay silent.
     */
    fun notifyTerminal(
        context: Context,
        kind: AppInstallFlow.TerminalKind,
        target: TerminalTarget,
        attempt: String?,
        detail: String? = null,
    ): Boolean {
        if (!AppInstallSessions.claimTerminal(target.pushId, attempt)) return false
        val display = target.label ?: target.packageName
        val (title, body) = when (kind) {
            AppInstallFlow.TerminalKind.SUCCESS ->
                context.getString(R.string.app_install_success_title, display) to
                    context.getString(R.string.app_install_success_body, display)
            AppInstallFlow.TerminalKind.USER_CANCELLED ->
                context.getString(R.string.app_install_user_cancelled_title) to
                    context.getString(R.string.app_install_user_cancelled_body, display)
            AppInstallFlow.TerminalKind.FAILED -> {
                val clean = detail?.take(MAX_DETAIL_CHARS)?.ifBlank { null }
                context.getString(R.string.app_install_failed_title) to
                    if (clean != null) {
                        context.getString(R.string.app_install_failed_body, display, clean)
                    } else {
                        context.getString(R.string.app_install_failed_body_plain, display)
                    }
            }
            // Hard refuse naming the package; the file is deleted by the caller.
            AppInstallFlow.TerminalKind.HASH_MISMATCH ->
                context.getString(R.string.app_install_blocked_title) to
                    context.getString(R.string.app_install_blocked_body, target.packageName)
            AppInstallFlow.TerminalKind.DOWNLOAD_FAILED ->
                context.getString(R.string.app_install_download_failed_title) to
                    context.getString(R.string.app_install_download_failed_body, target.packageName)
            AppInstallFlow.TerminalKind.TOO_LARGE ->
                context.getString(R.string.app_install_too_large_title) to
                    context.getString(R.string.app_install_too_large_body, target.packageName)
            AppInstallFlow.TerminalKind.DOWNLOAD_CANCELLED ->
                context.getString(R.string.app_install_cancelled_title) to
                    context.getString(R.string.app_install_cancelled_body, target.packageName)
        }
        postTerminal(context, target.pushId, title, body)
        return true
    }

    private fun postTerminal(context: Context, pushId: String, title: String, body: String) {
        val manager = context.notificationManager()
        NotificationChannels.ensureGeneral(manager)
        // The single notifyId owner is the payload ([AppInstallPayload.notifyIdFor]):
        // the terminal replaces the progress rather than stacking.
        manager.notify(
            AppInstallPayload.notifyIdFor(pushId),
            NotificationCompat.Builder(context, NotificationChannels.GENERAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun Context.notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
