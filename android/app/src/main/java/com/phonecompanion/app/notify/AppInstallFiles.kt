package com.phonecompanion.app.notify

import android.content.Context
import java.io.File

/**
 * Cache-dir home for downloaded APKs. Single owner of the directory +
 * file-name mapping ([AppInstallPayload.cacheFileNameFor]) so the
 * activity, the cancel receiver, and the installer-result receiver always
 * compute the identical path and always clean it up.
 */
object AppInstallFiles {

    private const val DIR = "app_push"

    fun apkFile(context: Context, payload: AppInstallPayload): File =
        apkFile(context, payload.pushId)

    fun apkFile(context: Context, pushId: String): File =
        File(File(context.cacheDir, DIR), AppInstallPayload.cacheFileNameFor(pushId))
}
