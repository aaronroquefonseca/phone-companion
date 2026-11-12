package com.phonecompanion.app.notify

import android.content.Context
import java.util.Base64

/**
 * Durable hop between "a shell command executed" and "its result reached
 * the server".
 *
 * Shell commands are at-most-once: marked before execution so a crash can
 * never re-run a destructive command. That means the result must survive
 * the process, not just the coroutine — otherwise a failed upload (or a
 * death between execution and upload) loses the output forever while the
 * command is already acked, as happened in the field.
 *
 * Entries are stored in plain prefs (command ids and command output are
 * not credentials, but output can be sensitive — the server DB is the
 * authoritative store and this queue is deleted on successful upload).
 * [prune] bounds the queue so an unreachable server cannot grow it
 * without limit.
 *
 * Pure storage logic with an injectable sink, so the queue is testable
 * without Android.
 */
object ShellResultQueue {

    /** Bounded queue: oldest entries beyond this are dropped. */
    const val MAX_ENTRIES = 10

    /** One pending upload. */
    data class Entry(
        val commandId: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
    )

    /** Backing store (prefs on Android, a map in tests). */
    interface Store {
        fun readAll(): Map<String, String>
        fun writeAll(entries: Map<String, String>)
    }

    private fun encode(entry: Entry): String {
        val enc = Base64.getEncoder()
        return listOf(
            entry.exitCode.toString(),
            if (entry.truncated) "1" else "0",
            enc.encodeToString(entry.stdout.toByteArray(Charsets.UTF_8)),
            enc.encodeToString(entry.stderr.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")
    }

    private fun decode(commandId: String, raw: String): Entry? {
        val parts = raw.split('\n', limit = 4)
        if (parts.size != 4) return null
        val exit = parts[0].toIntOrNull() ?: return null
        val truncated = when (parts[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return try {
            val dec = Base64.getDecoder()
            Entry(
                commandId = commandId,
                exitCode = exit,
                stdout = String(dec.decode(parts[2]), Charsets.UTF_8),
                stderr = String(dec.decode(parts[3]), Charsets.UTF_8),
                truncated = truncated,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun put(store: Store, entry: Entry) {
        val all = LinkedHashMap(store.readAll())
        all[entry.commandId] = encode(entry)
        while (all.size > MAX_ENTRIES) {
            val oldest = all.keys.firstOrNull() ?: break
            all.remove(oldest)
        }
        store.writeAll(all)
    }

    fun peek(store: Store, commandId: String): Entry? {
        val raw = store.readAll()[commandId] ?: return null
        return decode(commandId, raw)
    }

    fun remove(store: Store, commandId: String) {
        val all = LinkedHashMap(store.readAll())
        if (all.remove(commandId) != null) store.writeAll(all)
    }
}

/** Plain-prefs [ShellResultQueue.Store]. Never throws. */
class PrefsShellResultStore(context: Context) : ShellResultQueue.Store {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readAll(): Map<String, String> = try {
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    override fun writeAll(entries: Map<String, String>) {
        try {
            prefs.edit().clear().apply()
            val editor = prefs.edit()
            for ((k, v) in entries) editor.putString(k, v)
            editor.apply()
        } catch (_: Exception) {
            // Best effort: the server re-offers unacked commands.
        }
    }

    private companion object {
        const val PREFS_NAME = "pc_shell_results"
    }
}
