package com.phonecompanion.app.notify

import android.content.Context
import com.phonecompanion.app.data.AppMoshi
import com.squareup.moshi.Types

/**
 * Posted-ids set for poll-triggered install prompts: per-device command
 * ids the app already resolved, so a re-offered command (the app
 * died before acking, or the ack was lost) suppresses a duplicate
 * notification instead of stacking a second prompt.
 *
 * Plain [SharedPreferences][android.content.SharedPreferences], NOT
 * encrypted storage: command ids are opaque server-issued ids, not
 * secret, and the set must survive credential wipes/re-pairs (a re-pair
 * must not resurrect already-prompted commands as fresh notifications).
 * Pruned to [AppInstallPostedIds.MAX_POSTED_IDS] most-recent ids —
 * memory hygiene only, never a suppression window for live commands
 * (the server stops offering acked ids).
 */
interface AppInstallPostedStore {
    /** True when [commandId] already resolved (duplicate re-offer). */
    fun isPosted(commandId: String): Boolean

    /** Records [commandIds] as resolved (idempotent, pruned). */
    fun markPosted(commandIds: Collection<String>)
}

/**
 * Pure posted-ids list logic (zero Android imports): insertion-ordered
 * dedupe plus most-recent cap, and tolerant JSON-array persistence, so
 * the prune/restart behavior is JVM-testable without an emulator.
 */
object AppInstallPostedIds {

    /** Most-recent ids retained; older prompted ids fall off. */
    const val MAX_POSTED_IDS = 200

    /**
     * Merges [ids] insertion-ordered: re-added ids move to the back
     * (most-recent), blanks drop, and only the newest [max] survive.
     */
    fun prune(ids: List<String>, max: Int = MAX_POSTED_IDS): List<String> {
        val ordered = LinkedHashSet<String>()
        for (id in ids) {
            if (id.isBlank()) continue
            ordered.remove(id)
            ordered.add(id)
        }
        val merged = ordered.toList()
        return if (merged.size <= max) merged else merged.takeLast(max)
    }

    private val listType = Types.newParameterizedType(List::class.java, String::class.java)

    private val listAdapter by lazy {
        AppMoshi.moshi.adapter<List<String>>(listType)
    }

    /** Encodes ids as a JSON array string for prefs. Never throws. */
    fun encode(ids: List<String>): String = try {
        listAdapter.toJson(ids)
    } catch (_: Exception) {
        "[]"
    }

    /**
     * Decodes a prefs value back to ids. Tolerant: null/blank/garbage
     * yields empty (a corrupt entry loses dedupe for old ids — the ack
     * retry still converges — rather than crashing the poll).
     */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            listAdapter.fromJson(raw).orEmpty().filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Plain-prefs [AppInstallPostedStore] (production). Never throws. */
class PrefsAppInstallPostedStore(context: Context) : AppInstallPostedStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isPosted(commandId: String): Boolean {
        if (commandId.isBlank()) return false
        return try {
            AppInstallPostedIds.decode(prefs.getString(KEY_POSTED_IDS, null)).contains(commandId)
        } catch (_: Exception) {
            false
        }
    }

    override fun markPosted(commandIds: Collection<String>) {
        if (commandIds.isEmpty()) return
        try {
            val merged = AppInstallPostedIds.prune(
                AppInstallPostedIds.decode(prefs.getString(KEY_POSTED_IDS, null)) + commandIds,
            )
            prefs.edit().putString(KEY_POSTED_IDS, AppInstallPostedIds.encode(merged)).apply()
        } catch (_: Exception) {
            // Best-effort: losing dedupe retries a prompt at worst; the
            // ack still converges server-side.
        }
    }

    companion object {
        internal const val PREFS_NAME = "pc_app_install_posted"
        internal const val KEY_POSTED_IDS = "posted_push_ids"
    }
}
