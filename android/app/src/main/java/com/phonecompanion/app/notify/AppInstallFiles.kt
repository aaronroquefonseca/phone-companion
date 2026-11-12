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
     * Staging dir for Shizuku silent installs: the verified APK is copied
     * here before the privileged installer session reads its bytes (same
     * sanitized file name as the cache, same 24 h stale purge — see
     * [purgeStaleStaged]). The session writer is our own process, so no
     * cross-uid readability is required; the app-specific external dir
     * (`getExternalFilesDir("apk")`) is used for continuity with the
     * cache lifecycle, is not media-scanned, and needs no extra
     * permissions. Deleted after every silent terminal; the cache copy
     * follows the existing keep/delete rules.
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
     * for) the current one. Files a live flow still references (in-flight
     * downloads) are always skipped — never deleted mid-flow.
     */
    fun purgeStaleCache(context: Context, currentPushId: String) {
        runCatching {
            purgeStale(
                File(context.cacheDir, DIR),
                currentPushId,
                protectedFileNames = protectedFileNames(),
            )
        }
    }

    /**
     * Android entry point: purges stale staged APKs around [currentPushId]
     * in the external staged dir (same 24 h rule as the cache).
     * Call wherever [purgeStaleCache] is called so an abandoned staged copy
     * never lingers on shared storage; in-flight files are skipped.
     */
    fun purgeStaleStaged(context: Context, currentPushId: String) {
        runCatching {
            val root = context.getExternalFilesDir(STAGED_DIR) ?: return
            purgeStale(root, currentPushId, protectedFileNames = protectedFileNames())
        }
    }

    /**
     * Bounded retention sweep: deletes every entry older than the 24 h
     * retention in the cache dir and the staged dir, never an in-flight
     * (protected) file. Call on the poll path so abandoned files age out
     * even when no new command arrives (the purge above only runs around a
     * new command or an activity open).
     */
    fun sweepStale(context: Context) {
        val protected = protectedFileNames()
        runCatching { sweepDir(File(context.cacheDir, DIR), protected) }
        runCatching {
            val root = context.getExternalFilesDir(STAGED_DIR) ?: return@runCatching
            sweepDir(root, protected)
        }
    }

    /**
     * Deletes every file in [dir] that does not match [currentPushId]'s
     * cache name, plus the current entry itself when it is older than
     * [maxAgeMs] — except files named in [protectedFileNames] (in-flight
     * downloads a live flow still references). Returns the deleted count.
     * Pure JVM (dir-injected) so the purge rule is unit-testable; [nowMs]
     * is injectable for tests.
     */
    internal fun purgeStale(
        dir: File,
        currentPushId: String,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = STALE_APK_MAX_AGE_MS,
        protectedFileNames: Set<String> = emptySet(),
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
            if (f.name in protectedFileNames) continue
            val stale = f.name != keep || nowMs - f.lastModified() > maxAgeMs
            if (stale && f.delete()) deleted++
        }
        return deleted
    }

    /**
     * Deletes every file in [dir] older than [maxAgeMs], except files named
     * in [protectedFileNames]. Pure JVM (dir-injected) so the sweep rule is
     * unit-testable; [nowMs] is injectable for tests.
     */
    internal fun sweepDir(
        dir: File,
        protectedFileNames: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = STALE_APK_MAX_AGE_MS,
    ): Int {
        val files = try {
            dir.listFiles() ?: return 0
        } catch (_: Exception) {
            return 0
        }
        var deleted = 0
        for (f in files) {
            if (!f.isFile) continue
            if (f.name in protectedFileNames) continue
            if (nowMs - f.lastModified() > maxAgeMs && f.delete()) deleted++
        }
        return deleted
    }

    /** Sanitized cache names of every push id currently held by a live lock. */
    private fun protectedFileNames(): Set<String> =
        AppInstallDownloadLocks.inFlightPushIds()
            .mapTo(HashSet()) { AppInstallPayload.cacheFileNameFor(it) }
}
