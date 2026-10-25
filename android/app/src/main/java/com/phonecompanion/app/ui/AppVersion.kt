package com.phonecompanion.app.ui

/**
 * About-screen build identity (bug-report aid).
 *
 * Pure formatting over the BuildConfig fields baked at build time
 * (`VERSION_NAME`/`VERSION_CODE` from the Gradle config, `GIT_SHA` from
 * `git rev-parse --short HEAD` with a `"dev"` fallback when git metadata
 * is unavailable). Blank inputs render as `"dev"` so the line is never
 * empty — only [MainActivity] touches BuildConfig directly.
 */
object AppVersion {

    fun line(versionName: String?, versionCode: Int, gitSha: String?): String {
        val name = versionName?.takeIf { it.isNotBlank() } ?: "dev"
        val sha = gitSha?.takeIf { it.isNotBlank() } ?: "dev"
        return "Version $name ($versionCode) · $sha"
    }
}
