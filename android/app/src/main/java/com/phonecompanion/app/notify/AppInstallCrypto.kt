package com.phonecompanion.app.notify

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * SHA-256 verification for downloaded APKs, before any install attempt.
 *
 * Pure JVM (java.security only, zero Android imports) so the match/mismatch
 * paths are unit-testable without an emulator. Callers must hard-refuse the
 * install on mismatch: no install attempt, error notification naming the
 * package, file deleted. Logging discipline: log package + match/mismatch
 * only — never full hashes (and never tokens/credentials/keys).
 */
object AppInstallCrypto {

    /** Lowercase hex SHA-256 of [bytes]. Never throws (wraps in runCatching). */
    fun sha256Hex(bytes: ByteArray): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    /**
     * Streams [file] and compares its SHA-256 to [expectedHex] with a normal
     * equality check. Returns false (not an exception) for a missing file,
     * an unreadable file, or a digest failure — every non-match path refuses
     * the install identically.
     */
    fun verifyFile(file: File, expectedHex: String): Boolean {
        return try {
            if (!file.isFile) return false
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().toHex() == expectedHex
        } catch (_: Exception) {
            false
        }
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) out.append('0')
            out.append(v.toString(16))
        }
        return out.toString()
    }
}
