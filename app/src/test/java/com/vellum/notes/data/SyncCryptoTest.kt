package com.vellum.notes.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncCryptoTest {

    private val pass = "correct horse 2026".toCharArray()

    @Test
    fun roundTrip_recoversPlaintext() {
        val plain = "vellum backup bytes \u0000\u00ff end".toByteArray(Charsets.UTF_8)
        val blob = SyncCrypto.encrypt(plain, pass.copyOf())
        assertTrue(SyncCrypto.isEncrypted(blob))
        assertArrayEquals(plain, SyncCrypto.decrypt(blob, pass.copyOf()))
    }

    @Test
    fun encrypt_isRandomized() {
        val plain = "same input".toByteArray()
        val a = SyncCrypto.encrypt(plain, pass.copyOf())
        val b = SyncCrypto.encrypt(plain, pass.copyOf())
        assertFalse(a.contentEquals(b))
        // …but both decrypt.
        assertArrayEquals(plain, SyncCrypto.decrypt(a, pass.copyOf()))
        assertArrayEquals(plain, SyncCrypto.decrypt(b, pass.copyOf()))
    }

    @Test
    fun wrongPassphrase_failsClosed() {
        val blob = SyncCrypto.encrypt("secret".toByteArray(), pass.copyOf())
        try {
            SyncCrypto.decrypt(blob, "wrong passphrase!!".toCharArray())
            fail("wrong passphrase must not decrypt")
        } catch (e: javax.crypto.AEADBadTagException) {
            // Expected: authentication failure, no plaintext.
        }
    }

    @Test
    fun tamperedCiphertext_failsClosed() {
        val blob = SyncCrypto.encrypt("secret".toByteArray(), pass.copyOf())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte()
        try {
            SyncCrypto.decrypt(blob, pass.copyOf())
            fail("tampered payload must not decrypt")
        } catch (e: javax.crypto.AEADBadTagException) {
            // Expected.
        }
    }

    @Test
    fun plainZip_isNotEncrypted() {
        assertFalse(SyncCrypto.isEncrypted("PK\u0003\u0004".toByteArray()))
        assertFalse(SyncCrypto.isEncrypted(ByteArray(0)))
    }

    @Test
    fun decryptPlainZip_rejected() {
        try {
            SyncCrypto.decrypt("PK\u0003\u0004data".toByteArray(), pass.copyOf())
            fail("plain input must be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun shortPassphrase_rejected() {
        try {
            SyncCrypto.encrypt("x".toByteArray(), "short".toCharArray())
            fail("short passphrase must be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun emptyPayload_roundTrips() {
        val blob = SyncCrypto.encrypt(ByteArray(0), pass.copyOf())
        assertArrayEquals(ByteArray(0), SyncCrypto.decrypt(blob, pass.copyOf()))
    }

    @Test
    fun largePayload_roundTrips() {
        val plain = ByteArray(1024 * 1024) { (it * 31 % 251).toByte() }
        val blob = SyncCrypto.encrypt(plain, pass.copyOf())
        assertArrayEquals(plain, SyncCrypto.decrypt(blob, pass.copyOf()))
    }
}
