package com.phonecompanion.app.notify

import android.app.NotificationChannel
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
 * All posts go to the shared general channel ([NotificationPayload]
 * registry: no new channel — the registry pattern only mints per-kind
 * channels for generic kinds, and `app_install` is a dedicated flow, not a
 * generic kind). Every terminal state posts exactly one notification (or
 * in-app state): success, user-cancelled, failure, hash mismatch,
 * download error, too-large, download cancelled. Nothing here retries.
 *
 * Logging discipline: package names and push ids only — never APK URLs,
 * hashes, tokens, or credentials.
 */
object AppInstallNotifications {

    const val ACTION_CANCEL_DOWNLOAD = "pc_app_install_cancel"

    /** Tap target: explicit tap opens the pre-install UI. Never auto-installs. */
    fun promptIntent(context: Context, payload: AppInstallPayload): PendingIntent {
        val intent = Intent(context, AppInstallActivity::class.java)
            .putExtra(AppInstallPayload.EXTRA_PUSH_ID, payload.pushId)
            .putExtra(AppInstallPayload.EXTRA_APK_URL, payload.apkUrl)
            .putExtra(AppInstallPayload.EXTRA_SHA256, payload.sha256)
            .putExtra(AppInstallPayload.EXTRA_PACKAGE, payload.packageName)
            .putExtra(AppInstallPayload.EXTRA_VERSION, payload.version)
            .putExtra(AppInstallPayload.EXTRA_LABEL, payload.label)
        return PendingIntent.getActivity(
            context,
            CompanionMessagingService.tapRequestCode(payload.notifyId()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The install prompt: title is exactly `Install <label>?`. */
    fun notifyPrompt(context: Context, payload: AppInstallPayload) {
        val manager = context.notificationManager()
        manager.ensureGeneralChannel()
        val body = "${payload.packageName} • ${payload.displayVersion()}"
        manager.notify(
            payload.notifyId(),
            NotificationCompat.Builder(context, NotificationPayload.GENERAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Install ${payload.label}?")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(promptIntent(context, payload))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .build(),
        )
    }

    /**
     * Progress post (same id, so it replaces the prompt): determinate when
     * the server sent a usable Content-Length, indeterminate otherwise.
     * Carries the Cancel action that aborts the download and cleans up.
     */
    fun notifyProgress(
        context: Context,
        payload: AppInstallPayload,
        bytesRead: Long,
        totalBytes: Long?,
    ) {
        val manager = context.notificationManager()
        manager.ensureGeneralChannel()
        val cancel = PendingIntent.getBroadcast(
            context,
            CompanionMessagingService.actionRequestCode(payload.notifyId(), ACTION_CANCEL_DOWNLOAD),
            Intent(context, AppInstallCancelReceiver::class.java)
                .setAction(ACTION_CANCEL_DOWNLOAD)
                .putExtra(AppInstallPayload.EXTRA_PUSH_ID, payload.pushId)
                .putExtra(AppInstallPayload.EXTRA_PACKAGE, payload.packageName)
                .putExtra(AppInstallPayload.EXTRA_LABEL, payload.label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val determinate = totalBytes != null && totalBytes > 0
        manager.notify(
            payload.notifyId(),
            NotificationCompat.Builder(context, NotificationPayload.GENERAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_install_progress_title, payload.label))
                .setContentText("${payload.packageName} • ${payload.displayVersion()}")
                .setProgress(
                    if (determinate) totalBytes!!.toInt().coerceAtLeast(1) else 0,
                    bytesRead.toInt().coerceAtLeast(0),
                    !determinate,
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addAction(0, context.getString(R.string.app_install_cancel), cancel)
                .build(),
        )
    }

    fun notifySuccess(context: Context, label: String, pushId: String) {
        postTerminal(
            context,
            pushId,
            context.getString(R.string.app_install_success_title, label),
            context.getString(R.string.app_install_success_body, label),
        )
    }

    /** User-cancelled install: plain informational, NO retry. */
    fun notifyUserCancelled(context: Context, packageName: String, label: String?, pushId: String) {
        postTerminal(
            context,
            pushId,
            context.getString(R.string.app_install_user_cancelled_title),
            context.getString(R.string.app_install_user_cancelled_body, label ?: packageName),
        )
    }

    fun notifyFailed(context: Context, packageName: String, label: String?, pushId: String, message: String?) {
        val detail = message?.take(200)?.ifBlank { null }
        postTerminal(
            context,
            pushId,
            context.getString(R.string.app_install_failed_title),
            if (detail != null) {
                context.getString(R.string.app_install_failed_body, label ?: packageName, detail)
            } else {
                context.getString(R.string.app_install_failed_body_plain, label ?: packageName)
            },
        )
    }

    /** Hash mismatch: hard refuse naming the package; the file is deleted by the caller. */
    fun notifyHashMismatch(context: Context, packageName: String, pushId: String) {
        postTerminal(
            context,
            pushId,
            context.getString(R.string.app_install_blocked_title),
            context.getString(R.string.app_install_blocked_body, packageName),
        )
    }

    fun notifyDownloadFailed(context: Context, packageName: String, pushId: String) {
        postTerminal(
            context,
            pushId,
            context.getString(R.string.app_install_download_failed_title),
            context.getString(R.string.app_install_download_failed_body, packageName),
        )
    }

    fun notifyTooLarge(context: Context, packageName: String, pushId: String) {
        postTerminal(
            context,
            pushId,
            context.getString(R.string.app_install_too_large_title),
            context.getString(R.string.app_install_too_large_body, packageName),
        )
    }

    fun notifyDownloadCancelled(context: Context, packageName: String, pushId: String) {
        postTerminal(
            context,
            pushId,
            context.getString(R.string.app_install_cancelled_title),
            context.getString(R.string.app_install_cancelled_body, packageName),
        )
    }

    private fun postTerminal(context: Context, pushId: String, title: String, body: String) {
        val manager = context.notificationManager()
        manager.ensureGeneralChannel()
        // Re-notify the same stability domain the prompt/progress used so
        // the terminal replaces rather than stacks (see payload.notifyId).
        val notifyId = ("app_install:$pushId").hashCode()
        manager.notify(
            notifyId,
            NotificationCompat.Builder(context, NotificationPayload.GENERAL_CHANNEL_ID)
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

    private fun NotificationManager.ensureGeneralChannel() {
        if (getNotificationChannel(NotificationPayload.GENERAL_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NotificationPayload.GENERAL_CHANNEL_ID,
            NotificationKinds.displayName("unknown"),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = NotificationKinds.description("unknown")
        createNotificationChannel(channel)
    }
}
