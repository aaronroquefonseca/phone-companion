package com.phonecompanion.app.work

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prefs-backed pins for the SharedPrefs revocation marker.
 *
 * The marker must survive the credential wipe: it lives in the plain
 * `pc_poll_state` file while credentials live in encrypted storage, so
 * `SecureStorage.clearCredentials` (EncryptedSharedPreferences — covered
 * on-device, not here) can neither read nor disturb it. The wipe path
 * reconstructs state via `SharedPrefsPollStateStore(app)`, so a round-trip
 * across store instances on the same prefs pins exactly that survival.
 * Also pins missing-key→null and 0/negative→null normalization.
 */
class RevocationPrefsTest {

    private class FakePrefs : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun getAll(): Map<String, *> = data.toMap()
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            (data[key] as? Number)?.toFloat() ?: defValue
        override fun getInt(key: String, defValue: Int): Int =
            (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String, defValue: Long): Long =
            (data[key] as? Number)?.toLong() ?: defValue
        override fun getString(key: String, defValue: String?): String? =
            data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private class FakeEditor(
        private val data: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { data[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { data[key] = value }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { data[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { data[key] = value }
        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { data[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { data[key] = values }
        override fun remove(key: String): SharedPreferences.Editor =
            apply { data.remove(key) }
        override fun clear(): SharedPreferences.Editor =
            apply { data.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }

    /**
     * [ContextWrapper] with a null base: only the two overridden methods
     * are ever called, so the null base is never touched. Concrete (no
     * abstract-method burden) and on the unit-test classpath, unlike
     * `android.test.mock.MockContext`.
     */
    private class FakeContext : ContextWrapper(null) {
        private val files = mutableMapOf<String, FakePrefs>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            files.getOrPut(name) { FakePrefs() }

        fun rawPrefs(name: String): FakePrefs = files.getOrPut(name) { FakePrefs() }
    }

    private companion object {
        const val STAMP = 1_700_000_000_000L
    }

    @Test
    fun `stamp survives a fresh store on the same prefs (credential-wipe survival)`() {
        val context = FakeContext()
        SharedPrefsPollStateStore(context).revokedAtMs = STAMP

        // The wipe drops encrypted credentials, then every reader
        // reconstructs this store from the untouched plain prefs file.
        val reread = SharedPrefsPollStateStore(context)

        assertEquals(STAMP, reread.revokedAtMs)
    }

    @Test
    fun `marker lives outside encrypted credential storage`() {
        // Tautology guard: the marker file must stay the plain poll-state
        // file, never the encrypted credential file — otherwise the wipe
        // would take the marker with it.
        assertEquals("pc_poll_state", SharedPrefsPollStateStore.PREFS_NAME)
    }

    @Test
    fun `missing key reads null`() {
        assertNull(SharedPrefsPollStateStore(FakeContext()).revokedAtMs)
    }

    @Test
    fun `zero and negative raw values normalize to null`() {
        val context = FakeContext()
        val raw = context.rawPrefs(SharedPrefsPollStateStore.PREFS_NAME)

        raw.edit().putLong(SharedPrefsPollStateStore.KEY_REVOKED_AT_MS, 0L).apply()
        assertNull(SharedPrefsPollStateStore(context).revokedAtMs)

        raw.edit().putLong(SharedPrefsPollStateStore.KEY_REVOKED_AT_MS, -5L).apply()
        assertNull(SharedPrefsPollStateStore(context).revokedAtMs)

        // A real stamp written over the junk reads back intact.
        SharedPrefsPollStateStore(context).revokedAtMs = STAMP
        assertEquals(STAMP, SharedPrefsPollStateStore(context).revokedAtMs)
    }

    @Test
    fun `clearing removes the key`() {
        val context = FakeContext()
        val store = SharedPrefsPollStateStore(context)
        store.revokedAtMs = STAMP

        store.revokedAtMs = null

        assertNull(store.revokedAtMs)
        assertFalse(context.rawPrefs(SharedPrefsPollStateStore.PREFS_NAME)
            .contains(SharedPrefsPollStateStore.KEY_REVOKED_AT_MS))
    }
}
