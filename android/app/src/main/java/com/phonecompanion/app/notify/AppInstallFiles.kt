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
     * Shell-readable staging dir for Shizuku silent installs: the shell
     * UID (2000 over ADB) CANNOT read the app's private dirs
     * (`/data/data/<pkg>`), so verified APKs are copied here — the
     * app-specific external dir (`getExternalFilesDir("apk")`), which the
     * app can write and shell can read, is not media-scanned, and needs no
     * extra permissions. Same sanitized file name as the cache, same 24 h
     * stale purge (see [purgeStaleStaged]). Deleted after every silent
     * terminal; the cache copy follows the existing keep/delete rules.
     */
    private const val STAGED_DIR = "apk"

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
        File(pushDir(context.cacheDir), AppInstallPayload.cacheFileNameFor(pushId))

    /**
     * The app_push cache dir under [cacheDir]. Single owner so the cached
     * APK path, the fallback scope gate ([AppInstallFallback]), and the
     * FileProvider paths (`res/xml/app_install_filepaths.xml`, which covers
     * ONLY this dir — never the whole cache, never external dirs) always
     * agree.
     */
    internal fun pushDir(cacheDir: File): File = File(cacheDir, DIR)

    /**
     * Shell-readable staged copy of the verified APK for [pushId], or null
     * when external storage is unavailable (the silent branch then falls
     * back to the dialog flow — never a failure). Same sanitized file name
     * as the cache entry, so the traversal safety holds here too.
     */
    fun stagedApkFile(context: Context, pushId: String): File? {
        val root = try {
            context.getExternalFilesDir(STAGED_DIR)
        } catch (_: Exception) {
            null
        } ?: return null
        return File(root, AppInstallPayload.cacheFileNameFor(pushId))
    }

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
     * Android entry point: purges stale staged APKs around [currentPushId]
     * in the shell-readable external dir (same 24 h rule as the cache).
     * Call wherever [purgeStaleCache] is called so an abandoned staged copy
     * never lingers on shared storage.
     */
    fun purgeStaleStaged(context: Context, currentPushId: String) {
        runCatching {
            val root = context.getExternalFilesDir(STAGED_DIR) ?: return
            purgeStale(root, currentPushId)
        }
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
