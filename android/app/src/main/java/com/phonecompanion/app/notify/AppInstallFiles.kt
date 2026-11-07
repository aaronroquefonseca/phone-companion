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

    /**
     * Stale APK lifetime: `app_push/` entries not matching the current
     * push id — or older than this, even when they match — are deleted on
     * prompt post and on activity create (see [purgeStale]). 24 h keeps a
     * retry cheap while bounding leftover garbage from abandoned flows.
     */
    const val STALE_APK_MAX_AGE_HOURS = 24
    const val STALE_APK_MAX_AGE_MS = STALE_APK_MAX_AGE_HOURS * 60L * 60L * 1000L

    fun apkFile(context: Context, payload: AppInstallPayload): File =
        apkFile(context, payload.pushId)

    fun apkFile(context: Context, pushId: String): File =
        File(File(context.cacheDir, DIR), AppInstallPayload.cacheFileNameFor(pushId))

    /**
     * Android entry point: purges stale APKs around [currentPushId] in the
     * app cache dir. Call on prompt post and on activity create so an
     * abandoned or superseded download never sits next to (or is mistaken
     * for) the current one.
     */
    fun purgeStaleCache(context: Context, currentPushId: String) {
        runCatching { purgeStale(File(context.cacheDir, DIR), currentPushId) }
    }

    /**
     * Deletes every file in [dir] that does not match [currentPushId]'s
     * cache name, plus the current entry itself when it is older than
     * [maxAgeMs]. Returns the deleted count. Pure JVM (dir-injected) so
     * the purge rule is unit-testable; [nowMs] is injectable for tests.
     */
    internal fun purgeStale(
        dir: File,
        currentPushId: String,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = STALE_APK_MAX_AGE_MS,
    ): Int {
        val keep = AppInstallPayload.cacheFileNameFor(currentPushId)
        val files = try {
            dir.listFiles() ?: return 0
        } catch (_: Exception) {
            return 0
        }
        var deleted = 0
        for (f in files) {
            if (!f.isFile) continue
            val stale = f.name != keep || nowMs - f.lastModified() > maxAgeMs
            if (stale && f.delete()) deleted++
        }
        return deleted
    }
}
