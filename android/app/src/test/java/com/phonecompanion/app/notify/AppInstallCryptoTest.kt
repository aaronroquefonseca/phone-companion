package com.phonecompanion.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppInstallCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sha256 of known bytes matches`() {
        // SHA-256("abc") is a published test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AppInstallCrypto.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `verify match path accepts the file`() {
        val file = tmp.newFile("app.apk")
        file.writeBytes("fake-apk-bytes".toByteArray())
        val expected = AppInstallCrypto.sha256Hex("fake-apk-bytes".toByteArray())!!
        assertTrue(AppInstallCrypto.verifyFile(file, expected))
    }

    @Test
    fun `verify mismatch path refuses without throwing`() {
        val file = tmp.newFile("app.apk")
        file.writeBytes("fake-apk-bytes".toByteArray())
        // One nibble differs from the true digest.
        val wrong = "0" + AppInstallCrypto.sha256Hex("fake-apk-bytes".toByteArray())!!.drop(1)
        assertFalse(AppInstallCrypto.verifyFile(file, wrong))
    }

    @Test
    fun `verify missing file refuses without throwing`() {
        assertFalse(AppInstallCrypto.verifyFile(File(tmp.root, "absent.apk"), "a".repeat(64)))
    }

    @Test
    fun `verify directory refuses without throwing`() {
        assertFalse(AppInstallCrypto.verifyFile(tmp.root, "a".repeat(64)))
    }
}
